package com.connectai.agent.fastpath;

import com.connectai.agent.fastpath.IntentParser.Intent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IntentParserTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Use the available GitHub MCP tools to identify my authenticated GitHub account and report the login. Use real tool evidence and do not invent results. | WHO_AM_I",
            "who am i? | WHO_AM_I",
            "What is my GitHub username | WHO_AM_I",
            "Which repositories do I own? | MY_REPOS",
            "Which repositories does each of my accounts own? | MY_REPOS",
            "list my repos | MY_REPOS",
            "Which open pull requests are there in my repositories? | MY_PULL_REQUESTS",
            "show my merged PRs | MY_PULL_REQUESTS",
            "What are the latest commits in okruti-raghuraj/awsdemo1? | REPO_COMMITS",
            "last 5 commits of octo/api | REPO_COMMITS",
            "open pull requests in octo/api | REPO_PULL_REQUESTS",
            "closed issues in octo/api | REPO_ISSUES",
            "what is the latest release of octo/api | REPO_LATEST_RELEASE",
            "list releases of octo/api | REPO_RELEASES",
            "branches in octo/api | REPO_BRANCHES",
    })
    void recognisesCommonQuestions(String question, Intent expected) {
        assertThat(IntentParser.parse(question)).get().extracting(IntentParser.ParsedQuestion::intent).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "in which repo has the most guthub action failures",       // ranking + failures
            "Why did payment failures start after release 2.4.1?",     // reasoning
            "Compare octo/api and octo/web commits",                   // two repositories
            "which commits in octo/api caused the outage",             // causation
            "What is the weather today?",                              // unrelated
            "commits and issues in octo/api",                          // ambiguous mix
            "show my commits",                                         // needs a repository
            "pull requests in octo/api since monday",                  // time window: agent
    })
    void leavesEverythingElseToTheAgent(String question) {
        assertThat(IntentParser.parse(question)).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void extractsRepositoryStateAndLimit() {
        var parsed = IntentParser.parse("last 5 closed pull requests in octo/api-server?").orElseThrow();
        assertThat(parsed.owner()).isEqualTo("octo");
        assertThat(parsed.repo()).isEqualTo("api-server");
        assertThat(parsed.state()).isEqualTo("closed");
        assertThat(parsed.limit()).isEqualTo(5);
    }
}
