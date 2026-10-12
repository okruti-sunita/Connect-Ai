package com.connectai.domain;

/**
 * RUNNING      - the agent is still working.
 * ANSWERED     - the run completed and the answer is grounded in collected evidence.
 * PARTIAL      - some evidence was collected but the run stopped early or failed later;
 *                the answer is grounded but may be incomplete (see stopReason).
 * INCONCLUSIVE - the run ended normally but no usable evidence was found
 *                (no relevant tool, or tools returned nothing useful).
 * FAILED       - the run could not be carried out (no tools, model error, tool failures,
 *                server restart); no answer was produced.
 */
public enum InvestigationStatus {
    RUNNING,
    ANSWERED,
    PARTIAL,
    INCONCLUSIVE,
    FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
