/*
 * ---------------------------------------------------------------
 * Investigations API layer: the ONLY file that talks to
 * /api/investigations. Components call these functions and
 * never use fetch directly.
 *
 * The server speaks UPPER_CASE (ANSWERED, GITHUB, confidence 0-1).
 * This file converts that to what the screens use (resolved,
 * github, confidence 0-100). Nothing here invents data: steps,
 * evidence and answers are exactly what the backend persisted.
 * ---------------------------------------------------------------
 */
import { apiRequest } from "../../api/http";
import type {
    EvidenceCategory,
    EvidenceItem,
    InvestigationDetailData,
    InvestigationStatus,
    InvestigationStep,
    InvestigationSummary,
    ToolSource
} from "./types";

const BASE = "/investigations";

/* ---- Shapes returned by the server (InvestigationSummary / InvestigationResponse in the Java code) ---- */
type ApiStatus = "RUNNING" | "ANSWERED" | "PARTIAL" | "INCONCLUSIVE" | "FAILED";

interface ApiSummary {
    id: string;
    title: string;
    question: string;
    status: ApiStatus;
    archived: boolean;
    sources: string[];
    evidenceCount: number;
    createdAt: string;
}

interface ApiPage<T> {
    items: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

interface ApiEvidence {
    id: string;
    sourceType: string;
    sourceRef: string;
    summary: string;
    confidenceScore: number | null;
    fetchedAt: string;
    stepNumber: number | null;
    toolName: string | null;
    serverId: string | null;
    serverName: string | null;
}

interface ApiStep {
    stepNumber: number;
    toolName: string;
    status: "SUCCESS" | "FAILED";
    serverId: string | null;
    serverName: string | null;
    argumentsJson: string | null;
    reason: string | null;
    errorMessage: string | null;
    durationMs: number | null;
}

interface ApiInvestigation {
    id: string;
    title: string;
    question: string;
    status: ApiStatus;
    archived: boolean;
    resolvedAnswer: string | null;
    stopReason: string | null;
    createdAt: string;
    completedAt: string | null;
    evidence: ApiEvidence[];
    steps: ApiStep[];
}

/* ---- Conversions ---- */
const SOURCE_BY_API: Record<string, ToolSource> = {
    GITHUB: "github",
    JIRA: "jira",
    SPLUNK: "splunk",
    SLACK: "slack",
    QUEUE: "kafka",
    MCP: "mcp"
};

const CATEGORY_BY_SOURCE: Record<ToolSource, EvidenceCategory> = {
    github: "Code",
    jira: "Tickets",
    grafana: "Metrics",
    splunk: "Logs",
    kafka: "Logs",
    slack: "Chat",
    mcp: "Other"
};

/* Unknown source types are shown as generic MCP evidence rather than dropped. */
const toSource = (raw: string): ToolSource => SOURCE_BY_API[raw] ?? "mcp";

export const toStatus = (status: ApiStatus, archived: boolean): InvestigationStatus => {
    if (archived) return "archived";
    switch (status) {
        case "ANSWERED": return "resolved";
        case "PARTIAL": return "partial";
        case "INCONCLUSIVE": return "inconclusive";
        case "FAILED": return "failed";
        default: return "in-progress";
    }
};

export const STATUS_LABEL: Record<InvestigationStatus, string> = {
    resolved: "Answered",
    partial: "Partial",
    inconclusive: "Inconclusive",
    "in-progress": "Running",
    failed: "Failed",
    archived: "Archived"
};

export const timeAgo = (iso: string, now: number = Date.now()): string => {
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return "";
    const seconds = Math.max(0, Math.round((now - then) / 1000));
    if (seconds < 60) return "just now";
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    if (days < 30) return `${days}d ago`;
    return new Date(then).toLocaleDateString();
};

const uniqueSources = (raw: string[]): ToolSource[] => [...new Set(raw.map(toSource))];

const toSummary = (s: ApiSummary): InvestigationSummary => ({
    id: s.id,
    title: s.title,
    status: toStatus(s.status, s.archived),
    sources: uniqueSources(s.sources),
    updatedAgo: timeAgo(s.createdAt)
});

const toEvidence = (e: ApiEvidence): EvidenceItem => {
    const source = toSource(e.sourceType);
    return {
        id: e.id,
        source,
        category: CATEGORY_BY_SOURCE[source],
        title: e.sourceRef,
        snippet: e.summary,
        confidence: e.confidenceScore == null ? null : Math.round(e.confidenceScore * 100),
        when: timeAgo(e.fetchedAt),
        toolName: e.toolName,
        serverName: e.serverId ? e.serverName ?? "removed connection" : null,
        stepNumber: e.stepNumber
    };
};

const toStep = (step: ApiStep, source: ToolSource): InvestigationStep => ({
    id: `${step.stepNumber}-${step.toolName}`,
    stepNumber: step.stepNumber,
    source,
    toolName: step.toolName,
    serverName: step.serverId ? step.serverName ?? "removed connection" : null,
    status: step.status,
    argumentsJson: step.argumentsJson,
    reason: step.reason,
    errorMessage: step.errorMessage,
    durationMs: step.durationMs
});

/* ---- Public API used by the components ---- */
export interface ListQuery {
    status?: "all" | InvestigationStatus;
    q?: string;
    page?: number; // zero-based
    size?: number; // the server caps this at 50
}

const STATUS_FILTER: Partial<Record<InvestigationStatus, ApiStatus>> = {
    resolved: "ANSWERED",
    partial: "PARTIAL",
    inconclusive: "INCONCLUSIVE",
    "in-progress": "RUNNING",
    failed: "FAILED"
};

/* GET /api/investigations */
export const listInvestigations = async (query: ListQuery = {}): Promise<InvestigationSummary[]> => {
    const params = new URLSearchParams();
    if (query.status === "archived") {
        params.set("archived", "true");
    } else if (query.status && query.status !== "all") {
        params.set("status", STATUS_FILTER[query.status] ?? "RUNNING");
        params.set("archived", "false");
    }
    if (query.q?.trim()) params.set("q", query.q.trim());
    params.set("page", String(query.page ?? 0));
    params.set("size", String(query.size ?? 20));

    const page = await apiRequest<ApiPage<ApiSummary>>(`${BASE}?${params.toString()}`);
    return page.items.map(toSummary);
};

/*
 * POST /api/investigations   body: { question }
 * Returns immediately with the persisted record (normally RUNNING); poll getInvestigation for progress.
 */
export const startInvestigation = async (question: string): Promise<InvestigationSummary> => {
    const res = await apiRequest<ApiInvestigation>(BASE, "POST", { question });
    return {
        id: res.id,
        title: res.title,
        status: toStatus(res.status, res.archived),
        sources: uniqueSources(res.evidence.map((e) => e.sourceType)),
        updatedAgo: timeAgo(res.createdAt)
    };
};

/* GET /api/investigations/{id} */
export const getInvestigation = async (id: string): Promise<InvestigationDetailData> => {
    const res = await apiRequest<ApiInvestigation>(`${BASE}/${encodeURIComponent(id)}`);
    const evidence = res.evidence.map(toEvidence);
    const sourceByStep = new Map<number, ToolSource>();
    evidence.forEach((e) => { if (e.stepNumber != null) sourceByStep.set(e.stepNumber, e.source); });

    return {
        id: res.id,
        title: res.title,
        question: res.question,
        status: toStatus(res.status, res.archived),
        startedAgo: timeAgo(res.createdAt),
        completedAgo: res.completedAt ? timeAgo(res.completedAt) : null,
        stopReason: res.stopReason,
        tags: [], // entity extraction (ticket keys, PR numbers) comes with a later server step
        steps: (res.steps ?? []).map((s) => toStep(s, sourceByStep.get(s.stepNumber) ?? "mcp")),
        evidence,
        summary: {
            // ANSWERED/PARTIAL: grounded answer. INCONCLUSIVE/FAILED: the honest explanation of what went wrong.
            text: res.resolvedAnswer ?? "",
            keyEvidence: evidence.slice(0, 3).map((e) => ({ source: e.source, label: e.toolName ? `${e.title} · ${e.toolName}` : e.title })),
            nextSteps: []
        }
    };
};
