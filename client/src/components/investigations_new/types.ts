export type ToolSource = "github" | "jira" | "grafana" | "splunk" | "kafka" | "slack" | "mcp";

/** UI form of com.connectai.domain.InvestigationStatus (plus the archived flag). */
export type InvestigationStatus = "resolved" | "partial" | "inconclusive" | "in-progress" | "failed" | "archived";
export type EvidenceCategory = "Code" | "Tickets" | "Metrics" | "Logs" | "Chat" | "Other";

export const TERMINAL_STATUSES: InvestigationStatus[] = ["resolved", "partial", "inconclusive", "failed", "archived"];

export interface InvestigationSummary {
    id: string;
    title: string;
    status: InvestigationStatus;
    sources: ToolSource[];
    owner?: string; // initials; the server has no users yet, so this stays empty for now
    updatedAgo: string;
    /** FAST_PATH (deterministic, no model call), LEARNED (replayed plan) or AI_AGENT; null while running. */
    route?: string | null;
}

export interface InvestigationTag {
    label: string;
    accent?: boolean; // highlighted chip (ticket / PR / commit)
}

/** One real tool call recorded by the backend (InvestigationResponse.StepResponse). */
export interface InvestigationStep {
    id: string;
    stepNumber: number;
    source: ToolSource;
    toolName: string;
    /** Connection the call used ("GitHub - Work"); null when that connection was removed. */
    serverName: string | null;
    status: "SUCCESS" | "FAILED";
    /** Redacted JSON of the arguments actually sent. */
    argumentsJson: string | null;
    reason: string | null;
    errorMessage: string | null;
    durationMs: number | null;
}

export interface DiffLine {
    line: number;
    type: "add" | "del" | "ctx";
    text: string;
}

export interface EvidenceDetail {
    subtitle: string;
    whyItMatters: string;
    description: string;
    keyChanges: string[];
    diff: DiffLine[];
    reasoning: string;
}

export interface EvidenceItem {
    id: string;
    source: ToolSource;
    category: EvidenceCategory;
    /** Citation id used in the answer (E1, E2...) or a legacy source reference. */
    title: string;
    snippet: string;
    /** 0-100, only set by the legacy demo tools; null for real tool evidence. */
    confidence: number | null;
    when: string;
    toolName?: string | null;
    serverName?: string | null;
    stepNumber?: number | null;
    url?: string;
    detail?: EvidenceDetail;
}

export interface AnalysisSummary {
    text: string;
    keyEvidence: { source: ToolSource; label: string }[];
    nextSteps: string[];
}

export interface InvestigationDetailData {
    id: string;
    title: string;
    question: string;
    status: InvestigationStatus;
    startedAgo: string;
    completedAgo: string | null;
    startedBy?: string; // same: filled in once users exist
    /** Why the agent stopped (budget, no relevant tool, planner error...). */
    stopReason: string | null;
    /** FAST_PATH, LEARNED or AI_AGENT; null while running. */
    route: string | null;
    /** Why that route was taken: what was recognised, or why the AI planned it. */
    routeDetail: string | null;
    /** Wall-clock seconds from submission to completion; null while running. */
    durationSeconds: number | null;
    tags: InvestigationTag[];
    steps: InvestigationStep[];
    evidence: EvidenceItem[];
    summary: AnalysisSummary;
}
