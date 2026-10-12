package com.connectai.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class ToolShortlisterTest {

    private static final UUID GITHUB = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** A catalog shaped like the real GitHub MCP server's (names and descriptions only). */
    public static List<McpToolCandidate> githubLikeCatalog() {
        String[][] tools = {
                {"get_me", "Get details of the authenticated GitHub user. Use this when a request is about the user's own profile for GitHub."},
                {"search_users", "Find GitHub users by username, real name, or other profile information."},
                {"search_repositories", "Find GitHub repositories by name, description, readme, topics."},
                {"list_issues", "List issues in a GitHub repository."},
                {"get_issue", "Get details of a specific issue in a GitHub repository."},
                {"create_issue", "Create a new issue in a GitHub repository."},
                {"list_pull_requests", "List pull requests in a GitHub repository."},
                {"get_pull_request", "Get details of a specific pull request."},
                {"list_commits", "Get list of commits of a branch in a GitHub repository."},
                {"get_commit", "Get details for a commit from a GitHub repository."},
                {"get_file_contents", "Get the contents of a file or directory from a GitHub repository."},
                {"search_code", "Fast and precise code search across ALL GitHub repositories."},
                {"list_branches", "List branches in a GitHub repository."},
                {"list_releases", "List releases in a GitHub repository."},
                {"list_notifications", "Lists all GitHub notifications for the authenticated user."},
                {"get_team_members", "Get member usernames of a specific team in an organization."},
        };
        List<McpToolCandidate> catalog = new ArrayList<>();
        for (String[] tool : tools) {
            catalog.add(new McpToolCandidate(GITHUB, tool[0], "", tool[1], Map.of("type", "object")));
        }
        // Pad to a realistic size with unrelated tools.
        for (int i = 0; i < 30; i++) {
            catalog.add(new McpToolCandidate(GITHUB, "manage_workflow_" + i, "",
                    "Trigger or cancel a GitHub Actions workflow run number " + i, Map.of("type", "object")));
        }
        return catalog;
    }

    @Test
    void accountQuestionKeepsGetMeFirstWithinABoundedShortlist() {
        List<McpToolCandidate> shortlist = ToolShortlister.shortlist(
                "Use the available GitHub MCP tools to identify my authenticated GitHub account and report the login.",
                githubLikeCatalog(), 8);

        assertThat(shortlist).hasSizeLessThanOrEqualTo(8);
        assertThat(shortlist.get(0).toolName()).isEqualTo("get_me");
    }

    @Test
    void pullRequestQuestionPrefersPullRequestTools() {
        List<McpToolCandidate> shortlist = ToolShortlister.shortlist(
                "Which pull requests are open?", githubLikeCatalog(), 5);

        assertThat(shortlist).extracting(McpToolCandidate::toolName)
                .contains("list_pull_requests", "get_pull_request");
    }

    @Test
    void unrelatedQuestionMatchesNothing() {
        assertThat(ToolShortlister.shortlist("What is the weather today?", githubLikeCatalog(), 8)).isEmpty();
    }

    @Test
    void shortlistOnlyContainsCatalogEntries() {
        List<McpToolCandidate> catalog = githubLikeCatalog();
        List<McpToolCandidate> shortlist = ToolShortlister.shortlist("list issues and commits", catalog, 8);

        assertThat(catalog).containsAll(shortlist);
    }

    @Test
    void tokenizerSplitsSnakeAndCamelCase() {
        assertThat(ToolShortlister.tokens("get_me listPullRequests")).contains("me", "pull", "requests");
    }
}
