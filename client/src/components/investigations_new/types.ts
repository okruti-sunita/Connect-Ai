export type ToolSource = "github" | "jira" | "grafana" | "splunk" | "kafka" | "slack";
export type InvestigationStatus = "resolved" | "in-progress" | "archived";
export type EvidenceCategory = "Code" | "Tickets" | "Metrics" | "Logs" | "Chat";

export interface InvestigationSummary {
    id: string;
    title: string;
    status: InvestigationStatus;
    sources: ToolSource[];
    owner: string; // initials
    updatedAgo: string;
}

export interface InvestigationTag {
    label: string;
    accent?: boolean; // highlighted chip (ticket / PR / commit)
}

export interface InvestigationStep {
    id: string;
    source: ToolSource;
    text: string;
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
    title: string;
    snippet: string;
    confidence: number; // 0-100
    when: string;
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
    status: InvestigationStatus;
    startedAgo: string;
    startedBy: string;
    tags: InvestigationTag[];
    steps: InvestigationStep[];
    evidence: EvidenceItem[];
    summary: AnalysisSummary;
}
