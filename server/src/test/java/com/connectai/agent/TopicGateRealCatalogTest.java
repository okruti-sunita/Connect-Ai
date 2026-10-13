package com.connectai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Topic gate and shortlist against the real tool list of the remote GitHub MCP server
 * (fixture captured from a live discovery: names, descriptions, input schemas only).
 */
class TopicGateRealCatalogTest {

    private static List<McpToolCandidate> readOnlyCatalog;

    @BeforeAll
    static void load() throws Exception {
        UUID server = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        try (InputStream in = TopicGateRealCatalogTest.class.getResourceAsStream("/fixtures/github-mcp-tools.json")) {
            List<Map<String, Object>> raw = new ObjectMapper().readValue(in, new TypeReference<>() { });
            @SuppressWarnings("unchecked")
            List<McpToolCandidate> all = raw.stream().map(t -> new McpToolCandidate(server, (String) t.get("name"), "",
                    (String) t.get("description"), (Map<String, Object>) t.get("inputSchema"))).toList();
            assertThat(all).hasSize(46);
            readOnlyCatalog = all.stream().filter(ToolSafetyPolicy::isReadOnly).toList();
        }
    }

    @Test
    void writeToolsAreRemovedFromTheRealCatalog() {
        assertThat(readOnlyCatalog).extracting(McpToolCandidate::toolName)
                .contains("get_me", "search_repositories", "list_commits", "issue_read")
                .doesNotContain("create_repository", "fork_repository", "push_files", "delete_file",
                        "merge_pull_request", "issue_write", "create_or_update_file");
    }

    /** The reported question: the connected GitHub server has no Actions tools at all. */
    @Test
    void actionFailuresQuestionIsBlockedBecauseNoToolProvidesWorkflowRuns() {
        var coverage = ToolShortlister.topicCoverage("in which repo has the most guthub action failures", readOnlyCatalog);

        assertThat(coverage.topics()).containsExactly("action", "failures");
        assertThat(coverage.noToolCoversTheTopic()).isTrue();
    }

    @Test
    void myActionFailuresIsAlsoBlocked() {
        var coverage = ToolShortlister.topicCoverage("which of my repos has the most action failures", readOnlyCatalog);

        assertThat(coverage.noToolCoversTheTopic()).isTrue();
    }

    @Test
    void mixedQuestionFlagsTheDataNoToolProvides() {
        var coverage = ToolShortlister.topicCoverage("which repo has the most commits and action failures", readOnlyCatalog);

        assertThat(coverage.noToolCoversTheTopic()).isFalse();
        assertThat(coverage.uncoveredCapabilities()).contains("action", "failures");
    }

    @Test
    void entityQuestionsAreNeverGated() {
        var coverage = ToolShortlister.topicCoverage("What happened to PAY-123?", readOnlyCatalog);

        assertThat(coverage.topics()).isEmpty();
        assertThat(coverage.noToolCoversTheTopic()).isFalse();
    }

    @Test
    void accountQuestionKeepsGetMe() {
        String q = "Use the available GitHub MCP tools to identify my authenticated GitHub account and report the login.";
        var coverage = ToolShortlister.topicCoverage(q, readOnlyCatalog);

        assertThat(coverage.uncoveredCapabilities()).isEmpty();
        assertThat(ToolShortlister.shortlist(q, coverage.covering(), 8).get(0).toolName()).isEqualTo("get_me");
    }

    @Test
    void commitAndPullRequestQuestionsKeepTheRightTools() {
        var commits = ToolShortlister.topicCoverage("show the latest commits in octo/api", readOnlyCatalog);
        assertThat(commits.covering()).extracting(McpToolCandidate::toolName).contains("list_commits");
        assertThat(commits.uncoveredCapabilities()).isEmpty(); // "octo" and "api" are names, not capabilities

        var pulls = ToolShortlister.topicCoverage("which pull requests are open", readOnlyCatalog);
        assertThat(pulls.covering()).extracting(McpToolCandidate::toolName)
                .contains("list_pull_requests", "search_pull_requests");
    }

    /** Reported case: "each of my accounts" was blocked because only the singular had synonyms. */
    @Test
    void pluralCapabilityWordsUseTheSingularSynonyms() {
        var coverage = ToolShortlister.topicCoverage("Which repositories does each of my accounts own?", readOnlyCatalog);

        assertThat(coverage.noToolCoversTheTopic()).isFalse();
        assertThat(coverage.covering()).extracting(McpToolCandidate::toolName).contains("get_me");
    }

    /** Reported case: search_repositories was dropped for "which repositories does each account own". */
    @Test
    void toolsNamedAfterTheRequestedDataStayEligible() {
        String q = "Which repositories does each of my accounts own?";
        var coverage = ToolShortlister.topicCoverage(q, readOnlyCatalog);

        assertThat(ToolShortlister.withNameMatches(q, coverage.covering(), readOnlyCatalog))
                .extracting(McpToolCandidate::toolName).contains("get_me", "search_repositories");
    }

    @Test
    void singularRepositoryQuestionKeepsThePluralNamedRepositorySearchTool() {
        String q = "Find repository torvalds/linux and report its language and default branch.";
        var coverage = ToolShortlister.topicCoverage(q, readOnlyCatalog);

        assertThat(coverage.noToolCoversTheTopic()).isFalse();
        assertThat(ToolShortlister.withNameMatches(q, coverage.covering(), readOnlyCatalog))
                .extracting(McpToolCandidate::toolName).contains("search_repositories");
    }

    @Test
    void typoOfAGenericWordIsIgnored() {
        assertThat(ToolShortlister.topicCoverage("guthub repos", readOnlyCatalog).topics()).isEmpty();
    }
}
