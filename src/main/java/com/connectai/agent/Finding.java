package com.connectai.agent;

import com.connectai.domain.SourceType;

import java.util.Objects;

/**
 * One piece of evidence returned by a tool.
 * confidence is 0.0 - 1.0: the tool gives a first estimate, EvidenceRanker adjusts it.
 */
public record Finding(SourceType source, String sourceRef, String summary, double confidence) {

    public Finding {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(summary, "summary");
    }
}
