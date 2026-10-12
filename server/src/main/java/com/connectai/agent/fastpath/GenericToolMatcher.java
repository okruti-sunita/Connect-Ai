package com.connectai.agent.fastpath;

import com.connectai.agent.McpToolCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses a tool for a {@link GenericQuery} from any MCP server's live catalog, using only tool
 * names, descriptions and input schemas, and fills its arguments from parameter names.
 *
 * A tool qualifies only when every required parameter can be filled from a value the question
 * actually contains (or from the user's login for "my ..."); when two different tools tie, or a
 * requested filter has no matching parameter, nothing is chosen and the AI agent takes over.
 */
public final class GenericToolMatcher {

    /** A chosen tool. {@code loginParameter} is set when the call needs the user's login first. */
    public record Match(McpToolCandidate tool, Map<String, Object> arguments, String entityLabel,
                        String loginParameter, boolean loginIsId) {
    }

    private static final Set<String> LIST_VERBS = Set.of("list", "search", "get", "find", "query", "fetch", "read", "browse");
    private static final Set<String> GET_VERBS = Set.of("get", "read", "view", "fetch", "show", "describe", "lookup");
    private static final Set<String> IDENTITY_NAMES = Set.of("me", "whoami", "myself", "self", "currentuser", "authtest");

    private static final Set<String> OWNER_PARAMS = Set.of("owner", "org", "organization", "orgname", "namespace", "workspace");
    private static final Set<String> REPO_PARAMS = Set.of("repo", "repository", "reponame", "repositoryname");
    private static final Set<String> PROJECT_PARAMS = Set.of("project", "projectkey", "projectid", "space", "spacekey");
    private static final Set<String> KEY_PARAMS = Set.of("key", "issuekey", "ticketkey", "itemkey", "issueidorkey", "idorkey");
    private static final Set<String> NUMBER_PARAMS = Set.of("number", "issuenumber", "pullnumber", "prnumber",
            "ticketnumber", "pullrequestnumber");
    private static final Set<String> ID_PARAMS = Set.of("id", "itemid", "issueid", "ticketid", "recordid");
    private static final Set<String> TEXT_PARAMS = Set.of("query", "q", "search", "searchquery", "text", "term", "keyword", "keywords");
    private static final Set<String> STATE_PARAMS = Set.of("state", "status");
    private static final Set<String> LIMIT_PARAMS = Set.of("perpage", "limit", "maxresults", "pagesize", "first", "count", "size", "max");
    private static final Set<String> USER_PARAMS = Set.of("user", "username", "login", "assignee", "author", "creator",
            "reporter", "member", "userid", "accountid", "assigneeid", "authorid");
    private static final Set<String> CHANNEL_PARAMS = Set.of("channel", "channelid", "channelname", "room");

    /** Words people use for the same kind of item across tools (question word -> tool words). */
    private static final List<Set<String>> SYNONYMS = List.of(
            Set.of("ticket", "issue", "bug", "story", "task"),
            Set.of("pr", "pull", "merge"),
            Set.of("repo", "repository", "project"),
            Set.of("doc", "document", "page", "article"),
            Set.of("message", "post", "chat"),
            Set.of("build", "run", "workflow", "pipeline", "job"),
            Set.of("member", "user", "people", "collaborator"),
            Set.of("alert", "incident"),
            Set.of("release", "version"));

    private GenericToolMatcher() {
    }

    private static Set<String> expand(Set<String> words) {
        Set<String> out = new java.util.HashSet<>(words);
        for (Set<String> group : SYNONYMS) {
            if (words.stream().anyMatch(group::contains)) out.addAll(group);
        }
        return out;
    }

    /** Argument-free tools that return the signed-in user, one per connection. */
    public static List<McpToolCandidate> identityTools(List<McpToolCandidate> tools) {
        Map<java.util.UUID, McpToolCandidate> byServer = new LinkedHashMap<>();
        for (McpToolCandidate tool : tools) {
            if (isIdentityTool(tool)) {
                byServer.putIfAbsent(tool.serverId(), tool);
            }
        }
        return new ArrayList<>(byServer.values());
    }

    static boolean isIdentityTool(McpToolCandidate tool) {
        if (!required(tool).isEmpty()) return false;
        String joined = String.join("", nameTokens(tool.toolName()));
        boolean named = nameTokens(tool.toolName()).stream().anyMatch(IDENTITY_NAMES::contains)
                || IDENTITY_NAMES.stream().anyMatch(joined::endsWith);
        String description = tool.description().toLowerCase(Locale.ROOT);
        return named || description.contains("authenticated user") || description.contains("current user")
                || description.contains("currently authenticated") || description.contains("signed-in user");
    }

    public static Optional<Match> match(GenericQuery query, List<McpToolCandidate> tools) {
        record Scored(Match match, int score) { }
        List<Scored> scored = new ArrayList<>();
        Set<String> direct = new java.util.HashSet<>();
        query.entityWords().forEach(word -> direct.add(singular(word)));
        Set<String> wanted = expand(direct);
        boolean anyEntity = direct.isEmpty() && query.kind() == GenericQuery.Kind.GET; // "show PAY-123"

        for (McpToolCandidate tool : tools) {
            List<String> tokens = nameTokens(tool.toolName());
            List<String> entityTokens = tokens.stream().filter(t -> !LIST_VERBS.contains(t) && !GET_VERBS.contains(t)).toList();
            String entity = entityTokens.stream().filter(t -> wanted.contains(singular(t))).findFirst()
                    .orElse(anyEntity && !entityTokens.isEmpty() ? entityTokens.get(entityTokens.size() - 1) : null);
            if (entity == null) continue;

            int score = direct.contains(singular(entity)) ? 3 : anyEntity ? 1 : 2; // exact word beats synonym
            boolean plural = !singular(entity).equals(entity);
            if (query.kind() == GenericQuery.Kind.LIST) {
                if (tokens.contains("list")) score += 2;
                else if (tokens.contains("search")) score += query.quotedText() != null ? 3 : 1;
                else if (tokens.stream().anyMatch(LIST_VERBS::contains) && plural) score += 1;
                else continue;
            } else if (query.kind() == GenericQuery.Kind.GET) {
                if (tokens.stream().noneMatch(GET_VERBS::contains)) continue;
                if (plural) continue;
                score += 2;
            } else {
                continue;
            }

            Optional<Match> filled = fill(query, tool, entityTokens);
            if (filled.isEmpty()) continue;
            score += filled.get().arguments().size(); // more of the question's values used = better fit
            scored.add(new Scored(filled.get(), score));
        }

        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        if (scored.isEmpty()) return Optional.empty();
        Scored best = scored.get(0);
        boolean tie = scored.stream().skip(1)
                .anyMatch(other -> other.score() == best.score() && !other.match().tool().toolName().equals(best.match().tool().toolName()));
        return tie ? Optional.empty() : Optional.of(best.match());
    }

    @SuppressWarnings("unchecked")
    private static Optional<Match> fill(GenericQuery q, McpToolCandidate tool, List<String> entityTokens) {
        Map<String, Object> properties = tool.inputSchema().get("properties") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        List<String> required = required(tool);
        Map<String, Object> args = new LinkedHashMap<>();
        String loginParameter = null;
        boolean loginIsId = false;
        boolean usedState = false;

        for (Map.Entry<String, Object> property : properties.entrySet()) {
            String name = property.getKey();
            String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            Map<String, Object> schema = property.getValue() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            boolean isRequired = required.contains(name);
            Object value = null;

            if (OWNER_PARAMS.contains(normalized)) value = q.owner();
            else if (REPO_PARAMS.contains(normalized)) value = q.repo();
            else if (PROJECT_PARAMS.contains(normalized)) value = q.repo() != null ? q.repo()
                    : q.itemKey() != null ? q.itemKey().substring(0, q.itemKey().indexOf('-')) : null;
            else if (KEY_PARAMS.contains(normalized)) value = q.itemKey();
            else if (NUMBER_PARAMS.contains(normalized)) value = q.itemNumber();
            else if (ID_PARAMS.contains(normalized)) value = q.itemKey() != null ? q.itemKey() : q.itemNumber();
            else if (TEXT_PARAMS.contains(normalized)) value = q.quotedText();
            else if (CHANNEL_PARAMS.contains(normalized)) value = q.channel();
            else if (STATE_PARAMS.contains(normalized) && q.state() != null) {
                value = enumValue(schema, q.state()).orElse(null);
                if (value == null) return Optional.empty(); // a filter was asked for that this tool cannot apply
                usedState = true;
            } else if (LIMIT_PARAMS.contains(normalized)) {
                value = q.limit() != null ? (Object) (long) q.limit() : isRequired ? 20L : null;
            } else if (USER_PARAMS.contains(normalized) && q.firstPerson() && loginParameter == null) {
                loginParameter = name;
                loginIsId = normalized.endsWith("id");
                continue; // filled with the user's login after the identity call
            }

            if (value != null) {
                args.put(name, typed(schema, value));
            } else if (isRequired) {
                return Optional.empty(); // a required parameter the question gives no value for
            }
        }

        if (q.state() != null && !usedState) return Optional.empty();
        if (q.kind() == GenericQuery.Kind.GET && q.itemKey() == null && q.itemNumber() == null) return Optional.empty();
        if (q.firstPerson() && loginParameter == null && !personal(tool)) {
            return Optional.empty(); // "my ..." but nothing scopes the call to the user
        }
        String label = String.join(" ", entityTokens).replace('_', ' ');
        return Optional.of(new Match(tool, args, label, loginParameter, loginIsId));
    }

    /** Tools that are inherently about the signed-in user ("list my notifications", "assigned to you"). */
    private static boolean personal(McpToolCandidate tool) {
        String text = (tool.toolName() + " " + tool.description()).toLowerCase(Locale.ROOT);
        return text.matches(".*\\b(my|mine)\\b.*") || text.contains("authenticated user") || text.contains("current user")
                || text.contains("assigned to you") || text.contains("for the user");
    }

    private static Optional<Object> enumValue(Map<String, Object> schema, String wanted) {
        Object values = schema.get("enum");
        if (!(values instanceof List<?> list)) return Optional.of(wanted);
        return list.stream().filter(v -> String.valueOf(v).equalsIgnoreCase(wanted)).map(v -> (Object) v).findFirst();
    }

    private static Object typed(Map<String, Object> schema, Object value) {
        Object type = schema.get("type");
        if (("integer".equals(type) || "number".equals(type)) && value instanceof String text && text.matches("\\d+")) {
            return Long.parseLong(text);
        }
        if ("string".equals(type) && value instanceof Number number) {
            return String.valueOf(number);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static List<String> required(McpToolCandidate tool) {
        return tool.inputSchema().get("required") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
    }

    static List<String> nameTokens(String name) {
        String spaced = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        for (String token : spaced.split("[^a-z0-9]+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("sses") || word.endsWith("xes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss") && word.length() > 3) return word.substring(0, word.length() - 1);
        return word;
    }
}
