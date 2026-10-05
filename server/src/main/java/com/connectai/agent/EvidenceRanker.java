package com.connectai.agent;

import com.connectai.domain.SourceType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Decides which evidence is trusted most. Today: source weight + a confidence floor + a cap.
 * Later this is where recency and "how directly is this connected" get added.
 */
@Component
public class EvidenceRanker {

    static final double MIN_CONFIDENCE = 0.30;
    static final int MAX_ITEMS = 8;

    /** Chat is weaker evidence than logs or code history, so it is discounted. */
    private static final Map<SourceType, Double> SOURCE_WEIGHT = Map.of(
            SourceType.SPLUNK, 1.00,
            SourceType.GITHUB, 1.00,
            SourceType.QUEUE, 1.00,
            SourceType.JIRA, 0.95,
            SourceType.SLACK, 0.80
    );

    public List<Finding> rank(List<Finding> findings) {
        return findings.stream()
                .map(this::reweigh)
                .filter(f -> f.confidence() >= MIN_CONFIDENCE)
                .sorted(Comparator.comparingDouble(Finding::confidence).reversed())
                .limit(MAX_ITEMS)
                .toList();
    }

    private Finding reweigh(Finding f) {
        double weight = SOURCE_WEIGHT.getOrDefault(f.source(), 1.0);
        double adjusted = Math.max(0.0, Math.min(1.0, f.confidence() * weight));
        double rounded = Math.round(adjusted * 100.0) / 100.0;
        return new Finding(f.source(), f.sourceRef(), f.summary(), rounded);
    }
}
