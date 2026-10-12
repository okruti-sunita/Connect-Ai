package com.connectai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Facts about the authenticated user that earlier steps established, used to scope
 * first-person questions ("my repositories") to the user instead of a keyword search.
 *
 * The login is only taken from a successful call made without arguments (an identity tool
 * such as get_me) whose JSON output has a top-level "login" or "username".
 */
public final class IdentityFacts {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FIRST_PERSON = Pattern.compile("(?i)\\b(my|mine|i|me|myself|own|owned)\\b");
    private static final Set<String> QUERY_PARAMETERS = Set.of("query", "q", "search", "searchquery");

    private IdentityFacts() {
    }

    public static boolean isFirstPerson(String question) {
        return question != null && FIRST_PERSON.matcher(question).find();
    }

    /** Any established login (used where the connection does not matter). */
    public static Optional<String> authenticatedLogin(AgentExecutionState state) {
        return logins(state).values().stream().findFirst();
    }

    /** The login established per connection; each connection can be a different account. */
    public static Map<UUID, String> logins(AgentExecutionState state) {
        Map<UUID, String> logins = new LinkedHashMap<>();
        for (AgentExecutionStep step : state.steps()) {
            UUID serverId = step.plan().serverId();
            if (!logins.containsKey(serverId)) {
                authenticatedLogin(state, serverId).ifPresent(login -> logins.put(serverId, login));
            }
        }
        return logins;
    }

    public static Optional<String> authenticatedLogin(AgentExecutionState state, UUID serverId) {
        for (AgentExecutionStep step : state.steps()) {
            if (!step.plan().serverId().equals(serverId)
                    || step.status() != AgentStepStatus.SUCCESS || !step.plan().arguments().isEmpty()) {
                continue;
            }
            for (ToolExecutionContent content : step.result().content()) {
                Optional<String> login = loginFrom(content.text());
                if (login.isPresent()) {
                    return login;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * For a first-person question with a known login, a free-text search must be scoped to
     * that login; otherwise it searches everyone's data. Returns the problem, or empty when fine.
     */
    public static Optional<String> scopingProblem(String question, ToolSelectionPlan plan, AgentExecutionState state) {
        if (!isFirstPerson(question)) {
            return Optional.empty();
        }
        // Scope to the account of the connection being called, not another connection's account.
        Optional<String> login = authenticatedLogin(state, plan.serverId());
        if (login.isEmpty()) {
            return Optional.empty();
        }
        for (var entry : plan.arguments().entrySet()) {
            if (QUERY_PARAMETERS.contains(entry.getKey().toLowerCase(Locale.ROOT)) && entry.getValue() instanceof String query
                    && !scopedTo(query, login.get())) {
                return Optional.of("the question is about the user's own data, but the " + entry.getKey()
                        + " \"" + query + "\" searches everyone's. Set \"" + entry.getKey() + "\" to \"user:"
                        + login.get() + "\" (you may add other search terms after it)");
            }
        }
        return Optional.empty();
    }

    /**
     * The login must be used as a search qualifier (user:LOGIN, owner:LOGIN, author:LOGIN, repo:LOGIN/x...);
     * the bare login is only a keyword and matches anyone's data that mentions it.
     */
    static boolean scopedTo(String query, String login) {
        Pattern qualified = Pattern.compile("(?i)\\b(user|owner|org|author|assignee|involves|mentions|reviewed-by|"
                + "review-requested|commenter|committer|repo):" + Pattern.quote(login) + "(?![\\w-])");
        return qualified.matcher(query).find();
    }

    private static Optional<String> loginFrom(String text) {
        if (text == null || !text.trim().startsWith("{")) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(text);
            for (String field : new String[]{"login", "username"}) {
                if (node.path(field).isTextual() && !node.path(field).asText().isBlank()) {
                    return Optional.of(node.path(field).asText());
                }
            }
        } catch (Exception ignored) {
            // not JSON: no identity fact
        }
        return Optional.empty();
    }
}
