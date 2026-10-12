package com.connectai.agent.analysis;

import com.connectai.agent.AgentExecutionListener;
import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContext;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolSafetyPolicy;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.agent.fastpath.AnswerTemplates;
import com.connectai.agent.fastpath.GenericToolMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Cross-tool issue analysis: from one issue in a tracker (e.g. Jira) to the code changes most
 * likely behind it (e.g. GitHub), plus a time-to-resolve estimate from the tracker's own history.
 *
 * Deterministic and evidence-first, like the fast path: fixed read-only tool calls gather the
 * evidence, explicit scoring ranks suspect changes, and every line of the report cites the tool
 * result it came from. No model call. It names <em>suspects with reasons and a confidence</em>, not
 * a certain root cause, and says what it could not check.
 *
 * Signals: the issue key mentioned in commits/PRs, commit SHAs quoted in the issue, files and
 * exceptions named in the issue vs. files a commit changed, and how close a commit landed before
 * the issue was raised. Tools are found by capability (an issue-by-key tool, a JQL search tool,
 * GitHub's commit/PR tools), so any tracker exposing those shapes works.
 */
public class IssueAnalysisEngine {

    private static final Logger log = LoggerFactory.getLogger(IssueAnalysisEngine.class);

    private static final Pattern ANALYSIS_WORDS = Pattern.compile(
            "\\b(why|cause[sd]?|root|analy[sz]e|analysis|investigate|diagnose|debug|what happened|what broke|broke|"
                    + "behind|estimate|how long|time to|effort|fix|resolve|solve|culprit|introduced|regression)\\b");
    private static final Set<String> ISSUE_KEY_PARAMS = Set.of("issuekey", "issueidorkey", "key", "idorkey", "ticketkey");
    static final Duration WINDOW = Duration.ofHours(72);
    private static final int MAX_REPOS = 4;
    private static final int MAX_DETAILED = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    public record Result(AgentExecutionState execution, GroundedAnswer answer, String explanation) {
    }

    public record Decision(Optional<Result> result, String explanation) {
        static Decision declined(String why) {
            return new Decision(Optional.empty(), why);
        }
    }

    private final PlannedToolExecutor executor;
    private final GroundedAnswerContextBuilder contextBuilder;

    public IssueAnalysisEngine(PlannedToolExecutor executor, GroundedAnswerContextBuilder contextBuilder) {
        this.executor = executor;
        this.contextBuilder = contextBuilder;
    }

    /** The issue key when the question asks to analyse one issue ("why is PAY-12 failing", "estimate PAY-12"). */
    public static Optional<String> issueKey(String question) {
        if (question == null) return Optional.empty();
        Matcher key = IssueSignals.ISSUE_KEY.matcher(question);
        if (!key.find()) return Optional.empty();
        String first = key.group(1);
        if (key.find()) return Optional.empty(); // several issues: a comparison, left to the agent
        return ANALYSIS_WORDS.matcher(question.toLowerCase(Locale.ROOT)).find() ? Optional.of(first) : Optional.empty();
    }

    public Decision analyze(String question, List<McpToolCandidate> allTools, AgentExecutionListener listener) {
        Optional<String> key = issueKey(question);
        if (key.isEmpty()) return Decision.declined("Not an analysis of one issue key.");
        List<McpToolCandidate> tools = allTools.stream().filter(ToolSafetyPolicy::isReadOnly).toList();
        Optional<McpToolCandidate> issueTool = issueTool(tools);
        if (issueTool.isEmpty()) {
            return Decision.declined("The question names " + key.get()
                    + " but no connected tool reads an issue by key (connect Jira or another tracker).");
        }

        Run run = new Run(question, listener);
        // 1. The issue itself.
        Map<String, Object> issueArgs = new LinkedHashMap<>();
        issueArgs.put(keyParam(issueTool.get()), key.get());
        if (hasParam(issueTool.get(), "fields")) {
            issueArgs.put("fields", "summary,description,status,issuetype,priority,created,updated,resolutiondate,"
                    + "components,labels,assignee,reporter,project");
        }
        if (hasParam(issueTool.get(), "comment_limit")) issueArgs.put("comment_limit", 5);
        Optional<Step> issueStep = run.call(issueTool.get(), issueArgs, "Read " + key.get() + " from " + label(issueTool.get()) + ".");
        if (issueStep.isEmpty()) {
            return new Decision(Optional.of(run.finishFailed("Could not read " + key.get() + ": " + run.lastFailure)),
                    "Issue analysis for " + key.get() + " stopped: the tracker did not return the issue.");
        }
        IssueSignals issue = IssueSignals.from(issueStep.get().json);
        if (issue.key() == null) {
            return new Decision(Optional.of(run.finishFailed(key.get() + " was not found.")),
                    "Issue analysis for " + key.get() + " stopped: the tracker returned no issue.");
        }

        // 2. Code: repositories in scope, commits/PRs mentioning the key, commits just before it was raised.
        Map<String, Suspect> suspects = new LinkedHashMap<>();
        List<Suspect> linkedWork = new ArrayList<>();
        List<String> searched = new ArrayList<>();
        List<String> notCovered = new ArrayList<>();
        Set<String> repos = new LinkedHashSet<>(issue.repositories());
        List<McpToolCandidate> identities = named(tools, "get_me");
        Map<UUID, String> logins = new LinkedHashMap<>();
        for (McpToolCandidate me : identities) {
            if (named(tools, "search_commits").stream().noneMatch(t -> t.serverId().equals(me.serverId()))) continue;
            run.call(me, Map.of(), "Identity on " + label(me) + ", to scope the code search.").ifPresent(step -> {
                String login = AnswerTemplates.text(step.json, "login");
                if (login != null && !logins.containsValue(login)) logins.put(me.serverId(), login);
            });
        }
        if (logins.isEmpty() && repos.isEmpty()) {
            notCovered.add("code changes: no connected code host (e.g. GitHub) to search");
        }

        for (Map.Entry<UUID, String> account : logins.entrySet()) {
            UUID server = account.getKey();
            String scope = repos.isEmpty() ? "user:" + account.getValue()
                    : repos.stream().map(r -> "repo:" + r).collect(Collectors.joining(" "));
            tool(tools, server, "search_commits").flatMap(t -> run.call(t,
                    paged(t, Map.of("query", issue.key() + " " + scope), 20),
                    "Commits whose message mentions " + issue.key() + "."))
                    .ifPresent(step -> commits(step, server).forEach(s -> {
                        s.mentionsKey = true;
                        suspects.putIfAbsent(s.id(), s);
                    }));
            tool(tools, server, "search_pull_requests").flatMap(t -> run.call(t,
                    paged(t, Map.of("query", issue.key() + " is:pr " + scope), 20),
                    "Pull requests that mention " + issue.key() + "."))
                    .ifPresent(step -> pulls(step, server).forEach(s -> {
                        s.mentionsKey = true;
                        suspects.putIfAbsent(s.id(), s);
                    }));
            searched.add("commits and pull requests mentioning " + issue.key() + " (" + scope + ")");

            if (repos.isEmpty()) {
                tool(tools, server, "search_repositories").flatMap(t -> run.call(t,
                        paged(t, Map.of("query", "user:" + account.getValue() + " sort:updated"), 10),
                        "Repositories of " + account.getValue() + ", to check what changed just before " + issue.key() + "."))
                        .ifPresent(step -> AnswerTemplates.records(step.json).ifPresent(items -> items.forEach(item -> {
                            String name = AnswerTemplates.text(item, "full_name");
                            if (name != null && repos.size() < MAX_REPOS) repos.add(name);
                        })));
            }
        }

        if (issue.created() != null && !repos.isEmpty()) {
            Instant since = issue.created().minus(WINDOW);
            for (String repo : repos.stream().limit(MAX_REPOS).toList()) {
                UUID server = logins.isEmpty() ? null : logins.keySet().iterator().next();
                Optional<McpToolCandidate> list = server == null ? named(tools, "list_commits").stream().findFirst()
                        : tool(tools, server, "list_commits");
                if (list.isEmpty()) break;
                String[] parts = repo.split("/", 2);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("owner", parts[0]);
                args.put("repo", parts[1]);
                args.put("since", since.toString());
                args.put("until", issue.created().toString());
                run.call(list.get(), paged(list.get(), args, 20), "Commits to " + repo + " in the "
                        + WINDOW.toHours() + " h before " + issue.key() + " was raised.")
                        .ifPresent(step -> commits(step, list.get().serverId()).forEach(s -> {
                            if (s.repo == null) s.repo = repo;
                            suspects.merge(s.id(), s, (a, b) -> a);
                        }));
            }
            searched.add("commits in the " + WINDOW.toHours() + " h before it was raised in "
                    + String.join(", ", repos.stream().limit(MAX_REPOS).toList()));
        } else if (issue.created() == null) {
            notCovered.add("the time window: the issue has no creation date");
        }

        // 3. Score, then read the changed files: first of a fix that mentions the key (it shows where the bug
        // lives), then of the strongest suspects.
        suspects.values().forEach(s -> s.score(issue, Set.of()));
        Set<String> fixFiles = new LinkedHashSet<>();
        List<Suspect> ranked = rank(suspects.values());
        List<Suspect> order = new ArrayList<>();
        ranked.stream().filter(s -> isLinkedWork(s, issue) && "commit".equals(s.kind)).limit(1).forEach(order::add);
        ranked.stream().filter(s -> !isLinkedWork(s, issue)).forEach(order::add);
        int detailed = 0;
        for (Suspect s : order) {
            if (detailed >= MAX_DETAILED || !"commit".equals(s.kind) || s.repo == null) continue;
            Optional<McpToolCandidate> get = tool(tools, s.server, "get_commit").or(() -> named(tools, "get_commit").stream().findFirst());
            if (get.isEmpty()) break;
            String[] parts = s.repo.split("/", 2);
            Optional<Step> step = run.call(get.get(), Map.of("owner", parts[0], "repo", parts[1], "sha", s.sha),
                    "Files changed by " + s.shortSha() + ", to compare with what " + issue.key() + " describes.");
            detailed++;
            step.ifPresent(st -> {
                s.detailStep = st.number;
                JsonNode files = st.json.path("files");
                if (files.isArray()) files.forEach(f -> s.files.add(f.path("filename").asText()));
                s.linesChanged = st.json.path("stats").path("total").asInt(0);
                if (isLinkedWork(s, issue)) fixFiles.addAll(s.files);
            });
        }
        suspects.values().forEach(s -> s.score(issue, fixFiles));
        ranked = rank(suspects.values());
        // A change that mentions the key but landed after the issue was raised is the response to it, not its cause.
        for (Suspect s : new ArrayList<>(ranked)) {
            if (isLinkedWork(s, issue)) {
                linkedWork.add(s);
                ranked.remove(s);
            }
        }
        // Timing alone is not evidence: keep suspects with a content signal (or the single best one).
        Suspect top = ranked.isEmpty() ? null : ranked.get(0);
        ranked.removeIf(s -> s.score < 25 && !(s == top && s.score >= 10));

        // 4. Time to resolve, from the tracker's resolved history.
        Estimate estimate = estimate(run, tools, issue, notCovered);

        AgentExecutionState execution = run.finish(issue.key());
        GroundedAnswerContext context = contextBuilder.build(execution);
        Map<Integer, String> citations = new HashMap<>();
        context.evidence().forEach(e -> citations.put(e.stepNumber(), e.citation()));
        run.failures.forEach(f -> notCovered.add(f));

        String confidence = ranked.isEmpty() ? null : ranked.get(0).score >= 60 ? "high" : ranked.get(0).score >= 35 ? "medium" : "low";
        String report = report(issue, issueStep.get(), ranked, linkedWork, estimate, searched, notCovered, confidence, citations);
        GroundedAnswer.Coverage coverage = "high".equals(confidence) && estimate != null ? GroundedAnswer.Coverage.FULL
                : ranked.isEmpty() && estimate == null ? GroundedAnswer.Coverage.NONE : GroundedAnswer.Coverage.PARTIAL;
        GroundedAnswer answer = new GroundedAnswer(report, context.evidence(), execution.status(), true, false, coverage);

        String explanation = "Recognised an issue analysis of " + issue.key() + " → read it from " + label(issueTool.get())
                + (logins.isEmpty() ? "" : ", searched code on " + logins.size() + " GitHub account(s)")
                + ", scored " + suspects.size() + " candidate change(s) by key mentions, quoted SHAs, files/exceptions named in the issue and timing"
                + (estimate == null ? "" : ", and estimated time to resolve from " + estimate.sample + " resolved issue(s)")
                + ". Deterministic: no AI model call.";
        log.info("Issue analysis {}: {} candidate(s), top score {}, estimate {}", issue.key(), suspects.size(),
                ranked.isEmpty() ? 0 : ranked.get(0).score, estimate == null ? "none" : estimate.medianHours + "h");
        return new Decision(Optional.of(new Result(execution, answer, explanation)), explanation);
    }

    // ---------------------------------------------------------------- suspects

    static final class Suspect {
        final String kind;
        String repo;
        final String sha;
        final Integer number;
        final String title;
        final Instant when;
        final String url;
        final String author;
        final UUID server;
        final int step;
        Integer detailStep;
        boolean mentionsKey;
        final List<String> files = new ArrayList<>();
        int linesChanged;
        int score;
        final List<String> reasons = new ArrayList<>();

        Suspect(String kind, String repo, String sha, Integer number, String title, Instant when, String url,
                String author, UUID server, int step) {
            this.kind = kind;
            this.repo = repo;
            this.sha = sha;
            this.number = number;
            this.title = title == null ? "" : title;
            this.when = when;
            this.url = url;
            this.author = author;
            this.server = server;
            this.step = step;
        }

        String id() {
            return "commit".equals(kind) ? "c:" + sha : "p:" + repo + "#" + number;
        }

        String shortSha() {
            return sha == null ? "" : sha.substring(0, Math.min(7, sha.length()));
        }

        void score(IssueSignals issue, Set<String> fixFiles) {
            score = 0;
            reasons.clear();
            if (mentionsKey || title.contains(issue.key())) {
                score += 60;
                reasons.add("mentions " + issue.key());
            }
            if (sha != null && issue.shas().stream().anyMatch(s -> sha.startsWith(s) || s.startsWith(sha))) {
                score += 60;
                reasons.add("its SHA is quoted in the issue");
            }
            Set<String> names = issue.fileNames();
            List<String> overlap = files.stream()
                    .filter(f -> names.contains(f.substring(f.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)))
                    .limit(3).toList();
            if (!overlap.isEmpty()) {
                score += Math.min(2, overlap.size()) * 25;
                reasons.add("changed " + overlap.stream().map(f -> "`" + f + "`").collect(Collectors.joining(", "))
                        + ", named in the issue");
            }
            List<String> fixed = files.stream().filter(fixFiles::contains).limit(2).toList();
            if (!fixed.isEmpty() && !isLinkedWork(this, issue)) {
                score += 20;
                reasons.add("touched " + fixed.stream().map(f -> "`" + f + "`").collect(Collectors.joining(", "))
                        + ", which the fix for " + issue.key() + " also changes");
            }
            String text = title.toLowerCase(Locale.ROOT);
            List<String> exceptions = issue.exceptions().stream().filter(e -> text.contains(e.toLowerCase(Locale.ROOT))).toList();
            if (!exceptions.isEmpty()) {
                score += 15;
                reasons.add("its message names " + String.join(", ", exceptions));
            }
            if (issue.created() != null && when != null && !when.isAfter(issue.created())) {
                long hours = Duration.between(when, issue.created()).toHours();
                if (hours <= WINDOW.toHours()) {
                    score += Math.max(5, (int) Math.round(20 * (1 - hours / (double) WINDOW.toHours())));
                    reasons.add("landed " + ago(hours) + " before the issue was raised");
                }
            }
            List<String> words = issue.keywords().stream().filter(text::contains).limit(3).toList();
            if (!words.isEmpty()) {
                score += 4 * words.size();
                reasons.add("its message shares the issue's terms (" + String.join(", ", words) + ")");
            }
        }
    }

    private static boolean isLinkedWork(Suspect s, IssueSignals issue) {
        return (s.mentionsKey || s.title.contains(issue.key())) && issue.created() != null && s.when != null
                && s.when.isAfter(issue.created());
    }

    private static List<Suspect> rank(java.util.Collection<Suspect> suspects) {
        List<Suspect> ranked = new ArrayList<>(suspects);
        ranked.sort(Comparator.comparingInt((Suspect s) -> s.score).reversed()
                .thenComparing(s -> s.when == null ? Instant.EPOCH : s.when, Comparator.reverseOrder()));
        return ranked;
    }

    private static List<Suspect> commits(Step step, UUID server) {
        List<Suspect> out = new ArrayList<>();
        AnswerTemplates.records(step.json).ifPresent(items -> items.forEach(item -> {
            String sha = AnswerTemplates.text(item, "sha");
            if (sha == null) return;
            String message = AnswerTemplates.text(item, "commit.message", "message");
            String url = AnswerTemplates.text(item, "html_url", "url");
            String repo = AnswerTemplates.text(item, "repository.full_name");
            if (repo == null) repo = repoFromUrl(url);
            out.add(new Suspect("commit", repo, sha, null, firstLine(message),
                    IssueSignals.parseDate(AnswerTemplates.text(item, "commit.committer.date", "commit.author.date", "date")),
                    url, AnswerTemplates.text(item, "author.login", "commit.author.name"), server, step.number));
        }));
        return out;
    }

    private static List<Suspect> pulls(Step step, UUID server) {
        List<Suspect> out = new ArrayList<>();
        AnswerTemplates.records(step.json).ifPresent(items -> items.forEach(item -> {
            if (!item.path("number").isNumber()) return;
            String url = AnswerTemplates.text(item, "html_url", "url");
            String merged = AnswerTemplates.text(item, "merged_at", "pull_request.merged_at");
            Instant when = IssueSignals.parseDate(merged != null ? merged : AnswerTemplates.text(item, "created_at"));
            out.add(new Suspect("pull request", repoFromUrl(url), null, item.path("number").asInt(),
                    AnswerTemplates.text(item, "title"), when, url, AnswerTemplates.text(item, "user.login"), server, step.number));
        }));
        return out;
    }

    // ---------------------------------------------------------------- estimate

    record Estimate(double medianHours, double p75Hours, int sample, String basis, int step, Double actualHours) {
    }

    private Estimate estimate(Run run, List<McpToolCandidate> tools, IssueSignals issue, List<String> notCovered) {
        Double actual = issue.resolved() != null && issue.created() != null
                ? Duration.between(issue.created(), issue.resolved()).toMinutes() / 60.0 : null;
        Optional<McpToolCandidate> search = tools.stream().filter(t -> hasParam(t, "jql")
                && GenericToolMatcher.required(t).stream().allMatch(r -> r.equals("jql"))).findFirst();
        if (search.isEmpty() || issue.project() == null) {
            notCovered.add("time to resolve: no tracker search tool to read resolved history");
            return null;
        }
        String base = "project = \"" + issue.project() + "\" AND statusCategory = Done AND resolutiondate IS NOT EMPTY AND key != " + issue.key();
        List<String> attempts = new ArrayList<>();
        if (issue.issueType() != null) attempts.add(base + " AND issuetype = \"" + issue.issueType() + "\"");
        attempts.add(base);
        for (String jql : attempts) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("jql", jql + " ORDER BY resolutiondate DESC");
            if (hasParam(search.get(), "fields")) args.put("fields", "created,resolutiondate,priority,issuetype,components");
            if (hasParam(search.get(), "limit")) args.put("limit", 50);
            boolean typed = jql.contains("issuetype");
            Optional<Step> step = run.call(search.get(), args, "Resolved " + (typed ? issue.issueType() + " issues" : "issues")
                    + " in " + issue.project() + ", to estimate time to resolve.");
            if (step.isEmpty()) break;
            List<double[]> durations = new ArrayList<>(); // hours, same-priority flag
            AnswerTemplates.records(step.get().json).ifPresent(items -> items.forEach(item -> {
                Instant created = IssueSignals.parseDate(AnswerTemplates.text(item, "created", "fields.created"));
                Instant resolved = IssueSignals.parseDate(AnswerTemplates.text(item, "resolutiondate", "fields.resolutiondate"));
                if (created == null || resolved == null || resolved.isBefore(created)) return;
                String priority = AnswerTemplates.text(item, "priority.name", "fields.priority.name");
                durations.add(new double[]{Duration.between(created, resolved).toMinutes() / 60.0,
                        priority != null && priority.equals(issue.priority()) ? 1 : 0});
            }));
            if (durations.size() < 3) continue;
            List<Double> samePriority = durations.stream().filter(d -> d[1] == 1).map(d -> d[0]).toList();
            boolean byPriority = samePriority.size() >= 3;
            List<Double> values = byPriority ? samePriority : durations.stream().map(d -> d[0]).toList();
            String basis = values.size() + " resolved " + (typed ? issue.issueType() + " " : "") + "issue(s) in " + issue.project()
                    + (byPriority ? " with priority " + issue.priority() : "");
            return new Estimate(percentile(values, 50), percentile(values, 75), values.size(), basis, step.get().number, actual);
        }
        notCovered.add("time to resolve: fewer than 3 resolved issues in " + issue.project() + " to learn from");
        return null;
    }

    static double percentile(List<Double> values, int p) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    static String duration(double hours) {
        if (hours < 1) return Math.max(1, Math.round(hours * 60)) + " min";
        if (hours < 24) return Math.round(hours) + " h";
        double days = hours / 24;
        return (days < 10 ? String.format(Locale.ENGLISH, "%.1f", days) : String.valueOf(Math.round(days))) + " days";
    }

    // ---------------------------------------------------------------- report

    private static String report(IssueSignals issue, Step issueStep, List<Suspect> ranked, List<Suspect> linkedWork,
                                 Estimate estimate, List<String> searched, List<String> notCovered, String confidence,
                                 Map<Integer, String> citations) {
        StringBuilder out = new StringBuilder();
        out.append("**").append(issue.key()).append("** · ").append(issue.summary()).append(cite(citations, issueStep.number)).append("\n\n");
        List<String> facts = new ArrayList<>();
        if (issue.issueType() != null) facts.add(issue.issueType());
        if (issue.priority() != null) facts.add("priority " + issue.priority());
        if (issue.status() != null) facts.add("status " + issue.status());
        if (issue.created() != null) facts.add("raised " + DAY.format(issue.created()));
        if (!facts.isEmpty()) out.append(String.join(" · ", facts)).append("\n\n");

        List<String> clues = new ArrayList<>();
        if (!issue.files().isEmpty()) clues.add("files " + issue.files().stream().map(f -> "`" + f + "`").collect(Collectors.joining(", ")));
        if (!issue.exceptions().isEmpty()) clues.add("errors " + issue.exceptions().stream().map(e -> "`" + e + "`").collect(Collectors.joining(", ")));
        if (!issue.repositories().isEmpty()) clues.add("repositories " + String.join(", ", issue.repositories()));
        if (!issue.shas().isEmpty()) clues.add("commits " + String.join(", ", issue.shas()));
        out.append("**Clues in the issue:** ").append(clues.isEmpty() ? "none beyond its wording" : String.join("; ", clues)).append("\n\n");

        if (ranked.isEmpty()) {
            out.append("**Likely cause:** no code change in the evidence matches this issue, so no cause is named.\n\n");
        } else {
            out.append("**Most likely cause** (confidence: ").append(confidence).append("):\n");
            int n = 0;
            for (Suspect s : ranked) {
                if (++n > 3) break;
                out.append(n).append(". ").append(describe(s)).append(cite(citations, s.step))
                        .append(s.detailStep == null ? "" : cite(citations, s.detailStep)).append("\n");
                s.reasons.forEach(r -> out.append("   - ").append(r).append("\n"));
            }
            out.append("\n");
        }
        if (!linkedWork.isEmpty()) {
            out.append("**Work already linked to ").append(issue.key()).append("** (after it was raised):\n");
            linkedWork.stream().limit(3).forEach(s -> out.append("- ").append(describe(s)).append(cite(citations, s.step)).append("\n"));
            out.append("\n");
        }
        if (estimate != null) {
            out.append("**Estimated time to resolve:** about ").append(duration(estimate.medianHours()))
                    .append(" (median of ").append(estimate.basis()).append("; 75% were resolved within ")
                    .append(duration(estimate.p75Hours())).append(")").append(cite(citations, estimate.step()));
            if (estimate.actualHours() != null) {
                out.append(". This issue was actually resolved in ").append(duration(estimate.actualHours()));
            }
            out.append(".\n\n");
        }
        if (!searched.isEmpty()) {
            out.append("**Checked:** ").append(String.join("; ", searched)).append(".\n\n");
        }
        if (!notCovered.isEmpty()) {
            out.append("Not covered by the evidence:\n");
            notCovered.stream().distinct().forEach(n -> out.append("- ").append(n).append("\n"));
        }
        return out.toString().trim();
    }

    private static String describe(Suspect s) {
        String what = "commit".equals(s.kind)
                ? "Commit `" + s.shortSha() + "`" : "Pull request #" + s.number;
        return what + (s.repo == null ? "" : " in `" + s.repo + "`") + ": \"" + s.title + "\""
                + (s.author == null ? "" : " by " + s.author)
                + (s.when == null ? "" : ", " + DAY.format(s.when))
                + (s.linesChanged > 0 ? " (" + s.linesChanged + " lines changed)" : "");
    }

    private static String cite(Map<Integer, String> citations, int step) {
        String c = citations.get(step);
        return c == null ? "" : " [" + c + "]";
    }

    // ---------------------------------------------------------------- execution

    private record Step(int number, JsonNode json) {
    }

    private final class Run {
        final AgentExecutionListener listener;
        AgentExecutionState state;
        final List<String> failures = new ArrayList<>();
        String lastFailure;

        Run(String question, AgentExecutionListener listener) {
            this.listener = listener == null ? AgentExecutionListener.NONE : listener;
            this.state = AgentExecutionState.initial(question);
        }

        Optional<Step> call(McpToolCandidate tool, Map<String, Object> args, String reason) {
            int number = state.stepCount() + 1;
            AgentExecutionStep step = executor.execute(number,
                    new ToolSelectionPlan(tool.serverId(), tool.toolName(), args, "Issue analysis: " + reason));
            state = state.addStep(step);
            try {
                listener.onStep(step);
            } catch (RuntimeException ignored) {
                // progress persistence must not break the run
            }
            if (step.status() != AgentStepStatus.SUCCESS) {
                lastFailure = tool.toolName() + " on " + label(tool) + " failed"
                        + (step.errorMessage() == null ? "" : ": " + step.errorMessage());
                failures.add(lastFailure);
                return Optional.empty();
            }
            for (ToolExecutionContent content : step.result().content()) {
                Optional<JsonNode> json = AnswerTemplates.json(content.text());
                if (json.isPresent()) return Optional.of(new Step(number, json.get()));
            }
            return Optional.empty();
        }

        AgentExecutionState finish(String key) {
            return state.finish(state.hasSuccessfulStep() ? AgentExecutionStatus.COMPLETED : AgentExecutionStatus.FAILED,
                    "Issue analysis of " + key + ": " + state.stepCount() + " tool call(s), no AI model call.");
        }

        Result finishFailed(String reason) {
            AgentExecutionState execution = state.finish(AgentExecutionStatus.FAILED, reason);
            GroundedAnswer answer = new GroundedAnswer(reason, List.of(), execution.status(), false, false, GroundedAnswer.Coverage.NONE);
            return new Result(execution, answer, reason);
        }
    }

    // ---------------------------------------------------------------- tools

    static Optional<McpToolCandidate> issueTool(List<McpToolCandidate> tools) {
        return tools.stream().filter(t -> {
            List<String> tokens = GenericToolMatcher.nameTokens(t.toolName());
            if (!tokens.contains("issue") || tokens.stream().noneMatch(Set.of("get", "read", "view", "fetch")::contains)) return false;
            String key = keyParamOrNull(t);
            return key != null && GenericToolMatcher.required(t).stream().allMatch(key::equals);
        }).findFirst();
    }

    private static String keyParam(McpToolCandidate tool) {
        return keyParamOrNull(tool);
    }

    @SuppressWarnings("unchecked")
    private static String keyParamOrNull(McpToolCandidate tool) {
        if (!(tool.inputSchema().get("properties") instanceof Map<?, ?> properties)) return null;
        for (Object name : properties.keySet()) {
            String normalized = String.valueOf(name).replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
            if (ISSUE_KEY_PARAMS.contains(normalized)) return String.valueOf(name);
        }
        return null;
    }

    private static boolean hasParam(McpToolCandidate tool, String name) {
        return tool.inputSchema().get("properties") instanceof Map<?, ?> properties && properties.containsKey(name);
    }

    private static Map<String, Object> paged(McpToolCandidate tool, Map<String, Object> args, int size) {
        Map<String, Object> out = new LinkedHashMap<>(args);
        for (String name : List.of("perPage", "per_page", "limit")) {
            if (hasParam(tool, name)) {
                out.put(name, size);
                break;
            }
        }
        return out;
    }

    private static List<McpToolCandidate> named(List<McpToolCandidate> tools, String name) {
        return tools.stream().filter(t -> t.toolName().equals(name)).toList();
    }

    private static Optional<McpToolCandidate> tool(List<McpToolCandidate> tools, UUID server, String name) {
        return tools.stream().filter(t -> t.serverId().equals(server) && t.toolName().equals(name)).findFirst();
    }

    private static String label(McpToolCandidate tool) {
        return tool.serverName() == null || tool.serverName().isBlank() ? "the connection" : tool.serverName();
    }

    private static String repoFromUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("github\\.com/([^/]+)/([^/]+)/").matcher(url);
        return m.find() ? m.group(1) + "/" + m.group(2) : null;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        String line = text.strip().split("\\R", 2)[0];
        return line.length() > 100 ? line.substring(0, 97) + "..." : line;
    }

    private static String ago(long hours) {
        return hours < 1 ? "under an hour" : hours < 48 ? hours + " h" : (hours / 24) + " days";
    }
}
