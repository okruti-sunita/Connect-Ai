package com.connectai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSafetyPolicyTest {

    private static final UUID SERVER = UUID.randomUUID();

    private static McpToolCandidate tool(String name, Boolean readOnly) {
        return new McpToolCandidate(SERVER, name, "", "", Map.of(), readOnly);
    }

    /** Names from the real GitHub MCP catalog. */
    @ParameterizedTest
    @ValueSource(strings = {"create_repository", "fork_repository", "push_files", "delete_file", "merge_pull_request",
            "create_or_update_file", "issue_write", "sub_issue_write", "add_issue_comment", "update_pull_request",
            "request_copilot_review", "pull_request_review_write", "createIssue"})
    void mutatingNamesAreNotReadOnlyWithoutAnnotations(String name) {
        assertThat(ToolSafetyPolicy.isReadOnly(tool(name, null))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"get_me", "search_repositories", "list_commits", "issue_read", "pull_request_read",
            "get_file_contents", "search_code", "list_workflow_runs", "ui_get"})
    void readingNamesAreReadOnlyWithoutAnnotations(String name) {
        assertThat(ToolSafetyPolicy.isReadOnly(tool(name, null))).isTrue();
    }

    @Test
    void serverAnnotationWins() {
        assertThat(ToolSafetyPolicy.isReadOnly(tool("run_secret_scanning", true))).isTrue();
        assertThat(ToolSafetyPolicy.isReadOnly(tool("get_me", false))).isFalse();
    }
}
