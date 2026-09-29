package com.connectai.agent;

import com.connectai.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class EvidenceRankerTest {

    private final EvidenceRanker ranker = new EvidenceRanker();

    @Test
    void discountsChatEvidenceAndSortsStrongestFirst() {
        List<Finding> ranked = ranker.rank(List.of(
                new Finding(SourceType.SLACK, "#incidents", "chat", 0.78),
                new Finding(SourceType.SPLUNK, "logs", "log line", 0.98)));

        assertThat(ranked).extracting(Finding::source).containsExactly(SourceType.SPLUNK, SourceType.SLACK);
        assertThat(ranked.get(1).confidence()).isCloseTo(0.62, within(0.001)); // 0.78 * 0.80
    }

    @Test
    void dropsEvidenceBelowTheConfidenceFloor() {
        assertThat(ranker.rank(List.of(new Finding(SourceType.JIRA, "J-1", "weak", 0.2)))).isEmpty();
    }

    @Test
    void keepsAtMostMaxItems() {
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Finding(SourceType.GITHUB, "PR #" + i, "change " + i, 0.9));
        }
        assertThat(ranker.rank(many)).hasSize(EvidenceRanker.MAX_ITEMS);
    }
}
