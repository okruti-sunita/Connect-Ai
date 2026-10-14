package com.connectai.agent.fastpath;

import com.connectai.agent.AgentExecutionListener;
import com.connectai.agent.AgentExecutionState;
import com.connectai.agent.AgentExecutionStatus;
import com.connectai.agent.AgentExecutionStep;
import com.connectai.agent.AgentStepStatus;
import com.connectai.agent.GroundedAnswer;
import com.connectai.agent.GroundedAnswerContext;
import com.connectai.agent.GroundedAnswerContextBuilder;
import com.connectai.agent.GroundedEvidence;
import com.connectai.agent.McpToolCandidate;
import com.connectai.agent.ProviderConstraint;
import com.connectai.agent.PlannedToolExecutor;
import com.connectai.agent.ToolExecutionContent;
import com.connectai.agent.ToolSelectionPlan;
import com.connectai.agent.fastpath.IntentParser.Intent;
import com.connectai.agent.fastpath.IntentParser.ParsedQuestion;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Answers recognised questions without any model call: {@link IntentParser} -> a fixed recipe
 * of read-only tool calls (run through the normal validated executor, so steps and evidence are
 * recorded exactly like an agent run) -> {@link AnswerTemplates}.
 *
 * Returns empty when the question is not recognised or the connected tools cannot serve the
 * recipe; the caller then runs the AI agent. Recipes for "my ..." questions run on every
 * connection that has the tools, each scoped to that connection's own login.
 */
public class FastPathEngine {

    private static final Logger log = LoggerFactory.getLogger(FastPathEngine.class);

    /** {@code intent}: the recognised question type, e.g. "MY_REPOS" or "GENERIC_LIST: list tickets". */
    public record Result(AgentExecutionState execution, GroundedAnswer answer, String intent, boolean templateAnswer) {
    }

    /** Writes the answer when a template could not read the tool output. */
    @FunctionalInterface
    public interface AnswerFallback {
        GroundedAnswer generate(AgentExecutionState execution);
    }

    private final PlannedToolExecutor executor;
    private final GroundedAnswerContextBuilder contextBuilder;

    public FastPathEngine(PlannedToolExecutor executor, GroundedAnswerContextBuilder contextBuilder) {
        this.executor = executor;
        this.contextBuilder = contextBuilder;
    }

    /** The fast path's result (empty: use the AI agent) and a one-line explanation of the routing. */
    public record Decision(Optional<Result> result, String explanation) {
    }

    public Optional<Result> run(String question, List<McpToolCandidate> tools,
                                AgentExecutionListener listener, AnswerFallback fallback) {
        return decide(question, tools, listener, fallback).result();
    }

    public Decision decide(String question, List<McpToolCandidate> tools,
                           AgentExecutionListener listener, AnswerFallback fallback) {
        if (tools == null || tools.isEmpty()) {
            return new Decision(Optional.empty(), "No connected tools, so there is nothing to run.");
        }
        ProviderConstraint.Scope providerScope = ProviderConstraint.apply(question, tools);
        List<McpToolCandidate> routedTools = providerScope.constrained() ? providerScope.tools() : tools;
        if (providerScope.constrained()) {
            log.info("Fast path provider constraint applied: providerKeys={}", providerScope.providerKeys());
        }
        String judgement = IntentParser.reasoningWord(question);
        if (judgement != null) {
            return new Decision(Optional.empty(), "The question needs judgement (\"" + judgement
                    + "\"), so the AI agent plans it; templates only answer factual lookups.");
        }
        // 1. Recipes for well-known tool conventions (e.g. GitHub search qualifiers).
        Optional<ParsedQuestion> parsed = IntentParser.parse(question);
        Optional<List<Call>> recipe = parsed.flatMap(intent -> recipe(intent, routedTools));
        if (recipe.isEmpty()) {
            // 2. Generic: match any MCP server's tools by name and schema.
            Decision generic = runGeneric(question, routedTools, listener, fallback);
            if (generic.result().isEmpty() && parsed.isPresent()) {
                return new Decision(Optional.empty(), "Recognised " + parsed.get().intent()
                        + " but the connected tools cannot serve its recipe; " + lowerFirst(generic.explanation()));
            }
            return generic;
        }
        ParsedQuestion intent = parsed.get();
        log.info("Fast path: intent={}, owner={}, repo={}, state={}, limit={}",
                intent.intent(), intent.owner(), intent.repo(), intent.state(), intent.limit());

        Run run = new Run(question, listener);
        for (Call call : recipe.get()) {
            if (!run.execute(call)) {
                break;
            }
        }
        String explanation = "Recognised " + intent.intent()
                + (intent.owner() == null ? "" : " for " + intent.owner() + "/" + intent.repo())
                + (intent.state() == null ? "" : ", state " + intent.state())
                + (intent.limit() == null ? "" : ", last " + intent.limit())
                + " → fixed recipe: " + recipe.get().stream().map(c -> c.tool().toolName()).distinct()
                .collect(java.util.stream.Collectors.joining(" → "))
                + (recipe.get().stream().anyMatch(Call::dependsOnLogin) ? ", each scoped to that connection's login" : "")
                + ". Answer written from the tool output by a template.";
        return new Decision(Optional.of(finish(intent, run, fallback)), explanation);
    }

    // ---------------------------------------------------------------- recipes

    /** One planned call. {@code dependsOnLogin}: arguments are built after the identity call on that connection. */
    private record Call(McpToolCandidate tool, Map<String, Object> arguments, String reason, boolean dependsOnLogin,
                        java.util.function.Function<String, Map<String, Object>> withLogin, boolean loginIsId) {
        static Call fixed(McpToolCandidate tool, Map<String, Object> arguments, String reason) {
            return new Call(tool, arguments, reason, false, null, false);
        }

        static Call afterLogin(McpToolCandidate tool, java.util.function.Function<String, Map<String, Object>> withLogin, String reason) {
            return new Call(tool, Map.of(), reason, true, withLogin, false);
        }
    }

    private Optional<List<Call>> recipe(ParsedQuestion q, List<McpToolCandidate> tools) {
        List<Call> calls = new ArrayList<>();
        switch (q.intent()) {
            case WHO_AM_I -> named(tools, "get_me").forEach(me ->
                    calls.add(Call.fixed(me, Map.of(), "Identity of the account on " + label(me) + ".")));
            case MY_REPOS -> perConnection(tools, "search_repositories").forEach((me, search) -> {
                calls.add(Call.fixed(me, Map.of(), "Identity on " + label(me) + ", to scope the search."));
                calls.add(Call.afterLogin(search, login -> withPaging(search, Map.of("query", "user:" + login), 100),
                        "Repositories owned by the login of " + label(search) + "."));
            });
            case MY_PULL_REQUESTS -> {
                String qualifier = switch (q.state() == null ? "open" : q.state()) {
                    case "closed" -> " is:closed";
                    case "merged" -> " is:merged";
                    case "all" -> "";
                    default -> " is:open";
                };
                perConnection(tools, "search_pull_requests").forEach((me, search) -> {
                    calls.add(Call.fixed(me, Map.of(), "Identity on " + label(me) + ", to scope the search."));
                    calls.add(Call.afterLogin(search,
                            login -> withPaging(search, Map.of("query", "is:pr" + qualifier + " user:" + login), 50),
                            "Pull requests in repositories owned by the login of " + label(search) + "."));
                });
            }
            case REPO_COMMITS -> first(tools, "list_commits").ifPresent(t -> calls.add(Call.fixed(t,
                    withPaging(t, repoArgs(q), q.limit() == null ? 10 : q.limit()), "Latest commits of " + slug(q) + ".")));
            case REPO_BRANCHES -> first(tools, "list_branches").ifPresent(t -> calls.add(Call.fixed(t,
                    withPaging(t, repoArgs(q), 100), "Branches of " + slug(q) + ".")));
            case REPO_LATEST_RELEASE -> {
                Optional<McpToolCandidate> latest = first(tools, "get_latest_release");
                if (latest.isPresent()) {
                    calls.add(Call.fixed(latest.get(), repoArgs(q), "Latest release of " + slug(q) + "."));
                } else {
                    first(tools, "list_releases").ifPresent(t -> calls.add(Call.fixed(t, withPaging(t, repoArgs(q), 1),
                            "Latest release of " + slug(q) + ".")));
                }
            }
            case REPO_RELEASES -> first(tools, "list_releases").ifPresent(t -> calls.add(Call.fixed(t,
                    withPaging(t, repoArgs(q), q.limit() == null ? 10 : q.limit()), "Releases of " + slug(q) + ".")));
            case REPO_PULL_REQUESTS, REPO_ISSUES -> {
                String toolName = q.intent() == Intent.REPO_PULL_REQUESTS ? "list_pull_requests" : "list_issues";
                Optional<McpToolCandidate> tool = first(tools, toolName);
                if (tool.isPresent()) {
                    Map<String, Object> args = repoArgs(q);
                    String wanted = q.state() == null ? "open" : q.state();
                    Optional<Object> state = stateValue(tool.get(), wanted);
                    if (state.isEmpty() && !"open".equals(wanted)) {
                        return Optional.empty(); // e.g. "merged" is not a filter this tool has: let the agent handle it
                    }
                    state.ifPresent(value -> args.put("state", value));
                    calls.add(Call.fixed(tool.get(), withPaging(tool.get(), args, 50),
                            (q.intent() == Intent.REPO_PULL_REQUESTS ? "Pull requests" : "Issues") + " of " + slug(q) + "."));
                }
            }
        }
        return calls.isEmpty() ? Optional.empty() : Optional.of(calls);
    }

    // ---------------------------------------------------------------- execution

    private final class Run {
        final String question;
        final AgentExecutionListener listener;
        AgentExecutionState state;
        final Map<UUID, String> logins = new HashMap<>();
        final Map<UUID, String> ids = new HashMap<>();
        final Map<Integer, Call> callByStep = new LinkedHashMap<>();
        String failure;

        Run(String question, AgentExecutionListener listener) {
            this.question = question;
            this.listener = listener == null ? AgentExecutionListener.NONE : listener;
            this.state = AgentExecutionState.initial(question);
        }

        boolean execute(Call call) {
            Map<String, Object> arguments = call.arguments();
            if (call.dependsOnLogin()) {
                String login = call.loginIsId() ? ids.get(call.tool().serverId()) : logins.get(call.tool().serverId());
                if (login == null) {
                    return true; // identity failed on this connection: its failure is already recorded
                }
                arguments = call.withLogin().apply(login);
            }
            int number = state.stepCount() + 1;
            AgentExecutionStep step = executor.execute(number, new ToolSelectionPlan(
                    call.tool().serverId(), call.tool().toolName(), arguments, "Fast path: " + call.reason()));
            state = state.addStep(step);
            callByStep.put(number, call);
            try {
                listener.onStep(step);
            } catch (RuntimeException ignored) {
                // progress persistence must not break the run
            }
            if (step.status() != AgentStepStatus.SUCCESS) {
                failure = call.tool().toolName() + " on " + label(call.tool()) + " failed"
                        + (step.errorMessage() == null ? "" : ": " + step.errorMessage());
                return true; // other connections still run; calls needing this one's login are skipped
            }
            if (GenericToolMatcher.isIdentityTool(call.tool())) {
                rootJson(step).ifPresent(me -> {
                    String login = AnswerTemplates.text(me, "login", "username", "user.login", "name", "emailAddress", "email");
                    String id = AnswerTemplates.text(me, "accountId", "user_id", "id");
                    if (login != null) logins.put(call.tool().serverId(), login);
                    if (id != null) ids.put(call.tool().serverId(), id);
                });
            }
            return true;
        }
    }

    // ---------------------------------------------------------------- generic (any MCP server)

    private Decision runGeneric(String question, List<McpToolCandidate> tools,
                                AgentExecutionListener listener, AnswerFallback fallback) {
        Optional<GenericQuery> parsed = GenericQuery.parse(question);
        if (parsed.isEmpty()) {
            return new Decision(Optional.empty(),
                    "Not a recognised lookup (identity, list, or one item by reference), so the AI agent plans it.");
        }
        GenericQuery query = parsed.get();
        List<Call> calls = new ArrayList<>();
        String label;

        if (query.kind() == GenericQuery.Kind.IDENTITY) {
            List<McpToolCandidate> identities = GenericToolMatcher.identityTools(tools);
            if (identities.isEmpty()) {
                return new Decision(Optional.empty(), "No connected tool returns the signed-in user, so the AI agent plans it.");
            }
            identities.forEach(me -> calls.add(Call.fixed(me, Map.of(), "Identity of the account on " + label(me) + ".")));
            label = "GENERIC_IDENTITY";
        } else {
            GenericToolMatcher.MatchResult explained = GenericToolMatcher.explain(query, tools);
            Optional<GenericToolMatcher.Match> match = explained.match();
            if (match.isEmpty()) {
                log.info("Generic fast path: no tool clearly matches {} {}: {}", query.kind(), query.entityWords(), explained.reason());
                return new Decision(Optional.empty(), explained.reason() + " The AI agent plans it instead.");
            }
            GenericToolMatcher.Match m = match.get();
            label = "GENERIC_" + query.kind() + ": " + m.tool().toolName();
            String reason = (query.kind() == GenericQuery.Kind.GET ? "Read " : "List ") + m.entityLabel()
                    + " with " + m.tool().toolName() + ".";
            if (m.loginParameter() != null) {
                // "my ...": run on every connection that has the tool and an identity tool, scoped to its own user.
                List<McpToolCandidate> identities = GenericToolMatcher.identityTools(tools);
                for (McpToolCandidate tool : tools) {
                    if (!tool.toolName().equals(m.tool().toolName())) continue;
                    identities.stream().filter(me -> me.serverId().equals(tool.serverId())).findFirst().ifPresent(me -> {
                        calls.add(Call.fixed(me, Map.of(), "Identity on " + label(me) + ", to scope the call."));
                        calls.add(new Call(tool, Map.of(), reason, true, login -> {
                            Map<String, Object> args = new LinkedHashMap<>(m.arguments());
                            args.put(m.loginParameter(), login);
                            return args;
                        }, m.loginIsId()));
                    });
                }
                if (calls.isEmpty()) {
                    return new Decision(Optional.empty(), "\"My\" needs an identity tool on the connection of "
                            + m.tool().toolName() + ", and there is none.");
                }
            } else {
                calls.add(Call.fixed(m.tool(), m.arguments(), reason));
            }
        }

        log.info("Generic fast path: {} ({} call(s))", label, calls.size());
        Run run = new Run(question, listener);
        calls.forEach(run::execute);
        return new Decision(Optional.of(finishGeneric(query, label, run, fallback)), explainGeneric(query, tools, calls));
    }

    private static String explainGeneric(GenericQuery query, List<McpToolCandidate> tools, List<Call> calls) {
        if (query.kind() == GenericQuery.Kind.IDENTITY) {
            return "Recognised IDENTITY → identity tool on each connection ("
                    + calls.stream().map(c -> c.tool().toolName()).distinct().collect(java.util.stream.Collectors.joining(", "))
                    + "). Answer written from the tool output by a template.";
        }
        Optional<GenericToolMatcher.Match> match = GenericToolMatcher.match(query, tools);
        String arguments = match.map(m -> m.sources().entrySet().stream()
                .map(e -> e.getKey() + " ← " + e.getValue()).collect(java.util.stream.Collectors.joining("; "))).orElse("");
        return "Recognised " + query.kind() + " of \"" + String.join(" ", query.entityWords()) + "\" → tool "
                + match.map(m -> m.tool().toolName()).orElse("?") + " (matched by name and input schema, works for any MCP server)"
                + (arguments.isEmpty() ? "" : "; arguments: " + arguments) + ". Answer written from the tool output by a template.";
    }

    private static String lowerFirst(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private Result finishGeneric(GenericQuery query, String label, Run run, AnswerFallback fallback) {
        AgentExecutionState execution = run.state.finish(
                run.failure == null || run.state.hasSuccessfulStep() ? AgentExecutionStatus.COMPLETED : AgentExecutionStatus.FAILED,
                run.failure == null
                        ? "Answered by the fast path (" + label + "): " + run.state.stepCount() + " tool call(s), no AI model call."
                        : "Fast path (" + label + "): " + run.failure);
        GroundedAnswerContext context = contextBuilder.build(execution);
        Map<Integer, String> citations = new HashMap<>();
        context.evidence().forEach(e -> citations.put(e.stepNumber(), e.citation()));

        List<String> sections = new ArrayList<>();
        boolean several = run.callByStep.values().stream().map(c -> c.tool().serverId()).distinct().count() > 1;
        boolean ok = true; // failed calls are listed by templateAnswer(), they do not force the AI fallback
        for (AgentExecutionStep step : execution.steps()) {
            Call call = run.callByStep.get(step.stepNumber());
            String citation = citations.get(step.stepNumber());
            boolean identityStep = GenericToolMatcher.isIdentityTool(call.tool());
            if (step.status() != AgentStepStatus.SUCCESS || citation == null
                    || (identityStep && query.kind() != GenericQuery.Kind.IDENTITY)) {
                continue;
            }
            Optional<String> section = rootJson(step).flatMap(root -> switch (query.kind()) {
                case IDENTITY -> GenericRenderer.identity(root, citation);
                case GET -> GenericRenderer.single(root, singularLabel(call), citation);
                case LIST -> GenericRenderer.list(root, entityLabel(call), scope(query, run, call), citation);
            });
            if (section.isEmpty()) {
                ok = false;
                break;
            }
            sections.add((several ? "**" + label(call.tool()) + "**: " : "") + section.get());
        }
        if (!ok || sections.isEmpty()) {
            return new Result(execution, fallback.generate(execution), label, false);
        }
        return new Result(execution, templateAnswer(sections, run, execution, context), label, true);
    }

    private static String entityLabel(Call call) {
        List<String> tokens = GenericToolMatcher.nameTokens(call.tool().toolName()).stream()
                .filter(t -> !Set.of("list", "search", "get", "find", "query", "fetch", "read", "browse").contains(t)).toList();
        return tokens.isEmpty() ? "results" : String.join(" ", tokens);
    }

    private static String singularLabel(Call call) {
        List<String> tokens = GenericToolMatcher.nameTokens(call.tool().toolName()).stream()
                .filter(t -> !Set.of("get", "read", "view", "fetch", "show", "describe", "lookup").contains(t)).toList();
        return tokens.isEmpty() ? "item" : String.join(" ", tokens);
    }

    private static String scope(GenericQuery query, Run run, Call call) {
        if (query.owner() != null) return "in `" + query.owner() + "/" + query.repo() + "`";
        if (query.channel() != null) return "in #" + query.channel();
        if (call.dependsOnLogin() && run.logins.containsKey(call.tool().serverId())) {
            return "for **" + run.logins.get(call.tool().serverId()) + "**";
        }
        return null;
    }

    private Result finish(ParsedQuestion q, Run run, AnswerFallback fallback) {
        boolean anySuccess = run.state.hasSuccessfulStep();
        String reason = run.failure == null
                ? "Answered by the fast path (" + q.intent() + "): " + run.state.stepCount() + " tool call(s), no AI model call."
                : "Fast path (" + q.intent() + "): " + run.failure;
        AgentExecutionState execution = run.state.finish(
                run.failure == null || anySuccess ? AgentExecutionStatus.COMPLETED : AgentExecutionStatus.FAILED, reason);

        GroundedAnswerContext context = contextBuilder.build(execution);
        Map<Integer, String> citations = new HashMap<>();
        for (GroundedEvidence evidence : context.evidence()) {
            citations.put(evidence.stepNumber(), evidence.citation());
        }

        Optional<String> text = render(q, run, execution, citations);
        if (text.isEmpty()) {
            // A template could not read the output (or nothing succeeded): the regular answer step decides,
            // and its own checks (coverage, citations, claim check) apply.
            GroundedAnswer answer = fallback.generate(execution);
            return new Result(execution, answer, q.intent().name(), false);
        }
        return new Result(execution, templateAnswer(List.of(text.get()), run, execution, context), q.intent().name(), true);
    }

    /**
     * Template answer from the successful calls. Failed calls (e.g. one connection's token lacks a
     * scope) are listed, not hidden, and make the answer PARTIAL.
     */
    private static GroundedAnswer templateAnswer(List<String> sections, Run run, AgentExecutionState execution,
                                                 GroundedAnswerContext context) {
        List<String> failures = new ArrayList<>();
        for (AgentExecutionStep step : execution.steps()) {
            if (step.status() != AgentStepStatus.SUCCESS) {
                Call call = run.callByStep.get(step.stepNumber());
                failures.add("**" + (call == null ? "A connection" : label(call.tool())) + "**: " + step.plan().toolName()
                        + " failed" + (step.errorMessage() == null ? "." : " — " + step.errorMessage()));
            }
        }
        String text = String.join("\n\n", sections)
                + (failures.isEmpty() ? "" : "\n\nNot covered by the evidence:\n- " + String.join("\n- ", failures));
        return new GroundedAnswer(text, context.evidence(), execution.status(), true, false,
                failures.isEmpty() ? GroundedAnswer.Coverage.FULL : GroundedAnswer.Coverage.PARTIAL);
    }

    private Optional<String> render(ParsedQuestion q, Run run, AgentExecutionState execution, Map<Integer, String> citations) {
        List<String> sections = new ArrayList<>();
        boolean several = run.callByStep.values().stream().map(c -> c.tool().serverId()).distinct().count() > 1;

        for (AgentExecutionStep step : execution.steps()) {
            Call call = run.callByStep.get(step.stepNumber());
            String citation = citations.get(step.stepNumber());
            if (call == null || citation == null || step.status() != AgentStepStatus.SUCCESS) {
                continue;
            }
            boolean identityOnly = q.intent() == Intent.WHO_AM_I;
            if (!identityOnly && call.tool().toolName().equals("get_me")) {
                continue; // the identity step only scopes the real query
            }
            Optional<JsonNode> root = rootJson(step);
            if (root.isEmpty()) {
                return Optional.empty();
            }
            String login = run.logins.get(call.tool().serverId());
            Optional<String> section = switch (q.intent()) {
                case WHO_AM_I -> AnswerTemplates.identity(root.get(), citation);
                case MY_REPOS -> AnswerTemplates.repositories(root.get(), login, citation);
                case MY_PULL_REQUESTS -> AnswerTemplates.issuesOrPulls(root.get(),
                        stateWord(q.state(), "pull requests"), "in repositories owned by **" + login + "**", citation);
                case REPO_PULL_REQUESTS -> AnswerTemplates.issuesOrPulls(root.get(),
                        stateWord(q.state(), "pull requests"), "in `" + slug(q) + "`", citation);
                case REPO_ISSUES -> AnswerTemplates.issuesOrPulls(root.get(),
                        stateWord(q.state(), "issues"), "in `" + slug(q) + "`", citation);
                case REPO_COMMITS -> AnswerTemplates.commits(root.get(), slug(q), citation);
                case REPO_RELEASES, REPO_LATEST_RELEASE -> AnswerTemplates.releases(root.get(), slug(q), citation);
                case REPO_BRANCHES -> AnswerTemplates.branches(root.get(), slug(q), citation);
            };
            if (section.isEmpty()) {
                return Optional.empty();
            }
            String body = section.get();
            if (q.intent() == Intent.WHO_AM_I) {
                body = (several ? "**" + label(call.tool()) + "**: " : "") + capitalize(body);
            } else if (several) {
                body = "**" + label(call.tool()) + "**: " + body;
            }
            sections.add(body);
        }
        return sections.isEmpty() ? Optional.empty() : Optional.of(String.join("\n\n", sections));
    }

    // ---------------------------------------------------------------- helpers

    private static Optional<JsonNode> rootJson(AgentExecutionStep step) {
        for (ToolExecutionContent content : step.result().content()) {
            Optional<JsonNode> json = AnswerTemplates.json(content.text());
            if (json.isPresent()) {
                return json;
            }
        }
        if (step.result().structuredContent() != null) {
            try {
                return Optional.of(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(step.result().structuredContent()));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static List<McpToolCandidate> named(List<McpToolCandidate> tools, String name) {
        return tools.stream().filter(t -> t.toolName().equals(name)).toList();
    }

    private static Optional<McpToolCandidate> first(List<McpToolCandidate> tools, String name) {
        return named(tools, name).stream().findFirst();
    }

    /** get_me -> tool, for every connection that has both. */
    private static Map<McpToolCandidate, McpToolCandidate> perConnection(List<McpToolCandidate> tools, String toolName) {
        Map<McpToolCandidate, McpToolCandidate> pairs = new LinkedHashMap<>();
        for (McpToolCandidate me : named(tools, "get_me")) {
            named(tools, toolName).stream().filter(t -> t.serverId().equals(me.serverId())).findFirst()
                    .ifPresent(tool -> pairs.put(me, tool));
        }
        return pairs;
    }

    private static Map<String, Object> repoArgs(ParsedQuestion q) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("owner", q.owner());
        args.put("repo", q.repo());
        return args;
    }

    /** Adds a page size only when the tool declares one (perPage / per_page). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withPaging(McpToolCandidate tool, Map<String, Object> args, int size) {
        Map<String, Object> out = new LinkedHashMap<>(args);
        if (tool.inputSchema().get("properties") instanceof Map<?, ?> properties) {
            for (String name : List.of("perPage", "per_page", "first", "limit")) {
                if (((Map<String, Object>) properties).containsKey(name)) {
                    out.put(name, (long) size);
                    break;
                }
            }
        }
        return out;
    }

    /** The tool's own spelling of a state filter (e.g. "open" or "OPEN"); empty if it has no such value. */
    @SuppressWarnings("unchecked")
    private static Optional<Object> stateValue(McpToolCandidate tool, String wanted) {
        if (!(tool.inputSchema().get("properties") instanceof Map<?, ?> properties)
                || !(((Map<String, Object>) properties).get("state") instanceof Map<?, ?> state)) {
            return Optional.empty();
        }
        Object values = state.get("enum");
        if (!(values instanceof List<?> list)) {
            return Optional.of(wanted);
        }
        return list.stream().filter(v -> String.valueOf(v).equalsIgnoreCase(wanted)).map(v -> (Object) v).findFirst();
    }

    private static String stateWord(String state, String what) {
        if (state == null || "open".equals(state)) return "open " + what;
        if ("all".equals(state)) return what;
        return state + " " + what;
    }

    private static String slug(ParsedQuestion q) {
        return q.owner() + "/" + q.repo();
    }

    private static String label(McpToolCandidate tool) {
        return tool.serverName().isBlank() ? "the connection" : tool.serverName();
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }
}
