package com.connectai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Converts internal execution state into a bounded, user-safe evidence context.
 */
@Component
public class GroundedAnswerContextBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TOTAL_QUESTION = Pattern.compile(
            "(?i)\\b(total|totals|how many|how much|count|number of|zero|none)\\b");
    private static final Pattern YEAR_QUESTION = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    static final int MAX_EVIDENCE_ITEMS = 24;
    static final int MAX_CONTENT_CHARS = 4000;
    static final int MAX_TOTAL_EVIDENCE_CHARS = 24000;

    public GroundedAnswerContext build(AgentExecutionState state) {
        Objects.requireNonNull(state, "state");

        List<GroundedEvidence> evidence = new ArrayList<>();
        List<String> failedTools = new ArrayList<>();
        List<String> failureDetails = new ArrayList<>();
        List<String> qualityWarnings = new ArrayList<>();
        Map<String, String> pendingPages = new HashMap<>();
        Set<String> recordKeys = new HashSet<>();
        boolean hasDirectTotal = false;
        boolean hasExplicitPageEnd = false;
        boolean duplicateRecords = false;
        int totalChars = 0;

        for (AgentExecutionStep step : state.steps()) {
            if (step.status() == AgentStepStatus.FAILED || step.result().error()) {
                failedTools.add(step.plan().toolName());
                failureDetails.add("step " + step.stepNumber() + " " + step.plan().toolName() + ": "
                        + (step.errorMessage() == null ? "failed" : step.errorMessage()));
                continue;
            }

            if (evidence.size() >= MAX_EVIDENCE_ITEMS || totalChars >= MAX_TOTAL_EVIDENCE_CHARS) {
                break;
            }

            String content = renderResult(step.result());
            if (content.isBlank()) {
                continue;
            }

            PageMetadata pagination = new PageMetadata();
            JsonNode structured = step.result().structuredContent() == null ? null
                    : MAPPER.valueToTree(step.result().structuredContent());
            inspectResult(structured, pagination, recordKeys, step);
            hasDirectTotal |= containsDirectTotal(structured);
            if (step.result().content() != null) {
                for (ToolExecutionContent item : step.result().content()) {
                    if (item == null || item.text() == null) continue;
                    if (item.text().contains("[truncated ")) pagination.incomplete = true;
                    try {
                        JsonNode textJson = MAPPER.readTree(item.text());
                        inspectResult(textJson, pagination, structured == null ? recordKeys : new HashSet<>(), step);
                        hasDirectTotal |= containsDirectTotal(textJson);
                    } catch (Exception ignored) {
                        // Plain text remains evidence; only structured JSON carries machine-readable metadata.
                    }
                }
            }
            duplicateRecords |= pagination.duplicateRecords;
            hasExplicitPageEnd |= pagination.pageEnd;
            String scope = paginationScope(step);
            String requestedPage = requestedPage(step);
            String pending = pendingPages.get(scope);
            if (pending != null) {
                boolean continuation = pending.equals("*") ? requestedPage != null
                        : pending.equals(requestedPage);
                if (continuation && pagination.pageEnd) pendingPages.remove(scope);
                else if (continuation && pagination.nextCursor != null) pendingPages.put(scope, pagination.nextCursor);
            }
            if (pagination.incomplete) {
                qualityWarnings.add("A tool result was truncated or marked incomplete; totals may be partial.");
            }
            if (pagination.nextCursor != null) {
                pendingPages.put(scope, pagination.nextCursor);
            } else if (pagination.hasMore) {
                pendingPages.put(scope, "*");
            }

            int remainingChars = MAX_TOTAL_EVIDENCE_CHARS - totalChars;
            String boundedContent = truncate(content, Math.min(MAX_CONTENT_CHARS, remainingChars));
            if (boundedContent.isBlank()) {
                continue;
            }

            evidence.add(new GroundedEvidence(
                    "E" + (evidence.size() + 1),
                    step.stepNumber(),
                    step.plan().serverId(),
                    step.plan().toolName(),
                    boundedContent));
            totalChars += boundedContent.length();
        }

        if (!pendingPages.isEmpty()) {
            qualityWarnings.add("One or more tool results indicate additional pages that were not confirmed as retrieved.");
        }
        if (duplicateRecords) {
            qualityWarnings.add("Duplicate records appeared across tool results; count each unique record once.");
        }
        if (TOTAL_QUESTION.matcher(state.question()).find() && !hasDirectTotal
                && !hasExplicitPageEnd && pendingPages.isEmpty()) {
            // A returned list is not evidence that the full result set was retrieved unless the
            // tool provides a total or explicitly confirms pagination completion.
            qualityWarnings.add("The question asks for a total, but the results do not establish a complete total or page coverage.");
        }
        var yearMatcher = YEAR_QUESTION.matcher(state.question());
        if (yearMatcher.find()) {
            String year = yearMatcher.group(1);
            String nextYear = String.valueOf(Integer.parseInt(year) + 1);
            boolean scoped = state.steps().stream().filter(step -> step.status() == AgentStepStatus.SUCCESS)
                    .map(step -> StepAudit.argumentsJson(step.plan().arguments()))
                    .anyMatch(arguments -> arguments.contains("\"year\":" + year)
                            || arguments.contains(year + "-01-01") && (arguments.contains(year + "-12-31")
                            || arguments.contains(nextYear + "-01-01")));
            if (!scoped) {
                qualityWarnings.add("No successful tool call explicitly scoped its results to the requested year " + year + ".");
            }
        }

        return new GroundedAnswerContext(
                state.question(),
                state.status(),
                evidence,
                failedTools,
                failureDetails,
                qualityWarnings.stream().distinct().toList(),
                state.stopReason());
    }

    private static void inspectResult(JsonNode node, PageMetadata metadata, Set<String> recordKeys,
                                      AgentExecutionStep step) {
        if (node == null) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String key = entry.getKey().replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
                JsonNode value = entry.getValue();
                if ((key.equals("hasmore") || key.equals("incompleteresults") || key.equals("istruncated")
                        || key.equals("truncated")) && value.isBoolean()) {
                    if (value.asBoolean()) {
                        if (key.equals("hasmore")) metadata.hasMore = true;
                        else metadata.incomplete = true;
                    }
                    else if (key.equals("hasmore") || key.equals("incompleteresults")) metadata.pageEnd = true;
                }
                if (key.equals("nextcursor") || key.equals("nextpage") || key.equals("nexttoken")) {
                    if (value.isTextual() && !value.asText().isBlank()) metadata.nextCursor = value.asText();
                    else metadata.pageEnd = true;
                }
                if (value.isArray()) {
                    for (JsonNode record : value) {
                        if (record.isObject()) {
                            String identity = recordIdentity(record);
                            if (identity != null && !recordKeys.add(step.plan().serverId() + "|"
                                    + step.plan().toolName() + "|" + identity)) {
                                metadata.duplicateRecords = true;
                            }
                        }
                        inspectResult(record, metadata, recordKeys, step);
                    }
                } else if (value.isObject()) {
                    inspectResult(value, metadata, recordKeys, step);
                }
            });
        } else if (node.isArray()) {
            for (JsonNode record : node) {
                if (record.isObject()) {
                    String identity = recordIdentity(record);
                    if (identity != null && !recordKeys.add(step.plan().serverId() + "|"
                            + step.plan().toolName() + "|" + identity)) {
                        metadata.duplicateRecords = true;
                    }
                }
                inspectResult(record, metadata, recordKeys, step);
            }
        }
    }

    private static String recordIdentity(JsonNode record) {
        for (String key : List.of("node_id", "id", "key", "issue_key", "full_name", "sha")) {
            JsonNode value = record.get(key);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return key + "=" + value.asText();
            }
        }
        return null;
    }

    private static boolean containsDirectTotal(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().replaceAll("[_-]", "").equalsIgnoreCase("totalcount")
                        && field.getValue().isNumber()) return true;
                if (containsDirectTotal(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsDirectTotal(child)) return true;
        }
        return false;
    }

    private static String paginationScope(AgentExecutionStep step) {
        Map<String, Object> base = new TreeMap<>();
        step.plan().arguments().forEach((key, value) -> {
            String normalized = key.replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
            if (!Set.of("cursor", "page", "offset", "start", "continuation", "nexttoken", "pagetoken")
                    .contains(normalized)) base.put(key, value);
        });
        return step.plan().serverId() + "|" + step.plan().toolName() + "|" + base;
    }

    private static String requestedPage(AgentExecutionStep step) {
        for (var entry : step.plan().arguments().entrySet()) {
            String key = entry.getKey().replaceAll("[_-]", "").toLowerCase(Locale.ROOT);
            if (Set.of("cursor", "page", "offset", "start", "continuation", "nexttoken", "pagetoken")
                    .contains(key) && entry.getValue() != null) return String.valueOf(entry.getValue());
        }
        return null;
    }

    private static final class PageMetadata {
        private boolean incomplete;
        private boolean hasMore;
        private boolean pageEnd;
        private boolean duplicateRecords;
        private String nextCursor;
    }

    private static String renderResult(ToolExecutionResult result) {
        StringBuilder text = new StringBuilder();

        if (result.content() != null) {
            result.content().forEach(content -> {
                if (content == null) {
                    return;
                }
                if (content.text() != null && !content.text().isBlank()) {
                    if (!text.isEmpty()) {
                        text.append("\n");
                    }
                    text.append("[")
                            .append(content.type() == null ? "content" : content.type())
                            .append("] ")
                            .append(JsonCompactor.compact(content.text()));
                }
            });
        }

        if (result.structuredContent() != null) {
            if (!text.isEmpty()) {
                text.append("\n");
            }
            text.append("[structured] ")
                    .append(String.valueOf(result.structuredContent()));
        }

        return text.toString().trim();
    }

    private static String truncate(String value, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…";
    }
}
