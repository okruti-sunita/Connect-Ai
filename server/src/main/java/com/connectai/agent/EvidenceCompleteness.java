package com.connectai.agent;

/** What the successful source response lets Connect AI say about captured evidence coverage. */
public enum EvidenceCompleteness {
    COMPLETE,
    PARTIAL,
    TRUNCATED,
    EMPTY,
    UNKNOWN
}
