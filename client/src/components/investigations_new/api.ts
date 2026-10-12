/*
 * ---------------------------------------------------------------
 * Investigations API layer: the ONLY file that talks to
 * /api/investigations. Components call these three functions and
 * never use fetch directly.
 *
 * The server speaks UPPER_CASE (ANSWERED, GITHUB, confidence 0-1).
 * This file converts that to what the screens use (resolved,
 * github, confidence 0-100) so the components stay unchanged.
 * ---------------------------------------------------------------
 */
import type {
    EvidenceCategory,
    EvidenceItem,
    InvestigationDetailData,
    InvestigationStatus,
    InvestigationStep,
    InvestigationSummary,
    ToolSource
} from "./types";

const API_BASE = (
    (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? "http://localhost:8080/connect-ai/api"
).replace(/\/+$/, "");
const BASE = `${API_BASE}/investigations`;

/* ---- Shapes returned by the server (InvestigationSummary / InvestigationResponse in the Java code) ---- */
type ApiStatus = "RUNNING" | "ANSWERED" | "FAILED";

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
}

interface ApiInvestigation {
    id: string;
    title: string;
    question: string;
    status: ApiStatus;
    archived: boolean;
    resolvedAnswer: string | null;
    createdAt: string;
    evidence: ApiEvidence[];
    steps?: { stepNumber: number; toolName: string; status: "SUCCESS" | "FAILED" | string }[];
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

const toSource = (raw: string): ToolSource | null => SOURCE_BY_API[raw] ?? null;

const toStatus = (status: ApiStatus, archived: boolean): InvestigationStatus => {
    if (archived) return "archived";
    if (status === "ANSWERED") return "resolved";
    if (status === "FAILED") return "failed";
    return "in-progress";
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

const uniqueSources = (raw: string[]): ToolSource[] => [
    ...new Set(raw.map(toSource).filter((s): s is ToolSource => s !== null))
];

const toSummary = (s: ApiSummary): InvestigationSummary => ({
    id: s.id,
    title: s.title,
    status: toStatus(s.status, s.archived),
    sources: uniqueSources(s.sources),
    updatedAgo: timeAgo(s.createdAt)
});

const toEvidence = (e: ApiEvidence): EvidenceItem | null => {
    const source = toSource(e.sourceType);
    if (!source) return null; // Ignore unknown source types, but preserve generic MCP evidence.
    return {
        id: e.id,
        source,
        category: CATEGORY_BY_SOURCE[source],
        title: e.sourceRef,
        snippet: e.summary,
        confidence: Math.round((e.confidenceScore ?? 0) * 100),
        when: timeAgo(e.fetchedAt)
    };
};

/* Render only execution records persisted by the backend; never infer steps from evidence. */
const stepsFrom = (raw: NonNullable<ApiInvestigation["steps"]>): InvestigationStep[] =>
    raw.map((step) => {
        const name = (step.toolName ?? "").toLowerCase();
        const source: ToolSource = name.includes("github") ? "github"
            : name.includes("slack") ? "slack"
            : name.includes("jira") ? "jira"
            : name.includes("splunk") ? "splunk"
            : name.includes("grafana") ? "grafana"
            : name.includes("kafka") || name.includes("queue") ? "kafka" : "mcp";
        return {
            id: `${step.stepNumber}-${step.toolName}`,
            source,
            text: `${step.toolName} — ${step.status}`
        };
    });

const request = async <T>(url: string, init?: RequestInit): Promise<T> => {
    const res = await fetch(url, {
        ...init,
        headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}) }
    });
    if (!res.ok) throw new Error(`API failed with status: ${res.status}`);
    return res.json() as Promise<T>;
};

/* ---- Public API used by the components ---- */
export interface ListQuery {
    status?: "all" | InvestigationStatus;
    q?: string;
    page?: number; // zero-based
    size?: number; // the server caps this at 50
}

/* GET /api/investigations */
export const listInvestigations = async (query: ListQuery = {}): Promise<InvestigationSummary[]> => {
    const params = new URLSearchParams();
    switch (query.status) {
        case "resolved":
            params.set("status", "ANSWERED");
            params.set("archived", "false");
            break;
        case "in-progress":
            params.set("status", "RUNNING");
            params.set("archived", "false");
            break;
        case "failed":
            params.set("status", "FAILED");
            params.set("archived", "false");
            break;
        case "archived":
            params.set("archived", "true");
            break;
        default:
            break; // "all": no filter
    }
    if (query.q?.trim()) params.set("q", query.q.trim());
    params.set("page", String(query.page ?? 0));
    params.set("size", String(query.size ?? 20));

    const page = await request<ApiPage<ApiSummary>>(`${BASE}?${params.toString()}`);
    return page.items.map(toSummary);
};

/* POST /api/investigations   body: { question }   (the server runs the agent and returns the finished run) */
export const startInvestigation = async (question: string): Promise<InvestigationSummary> => {
    const res = await request<ApiInvestigation>(BASE, { method: "POST", body: JSON.stringify({ question }) });
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
    const res = await request<ApiInvestigation>(`${BASE}/${encodeURIComponent(id)}`);
    const evidence = res.evidence
        .map(toEvidence)
        .filter((e): e is EvidenceItem => e !== null)
        .sort((a, b) => b.confidence - a.confidence);

    return {
        id: res.id,
        title: res.title,
        status: toStatus(res.status, res.archived),
        startedAgo: timeAgo(res.createdAt),
        tags: [], // entity extraction (ticket keys, PR numbers) comes with a later server step
        steps: stepsFrom(res.steps ?? []),
        evidence,
        summary: {
            // A failed run stores the raw error text; never show that to users.
            text: res.status === "FAILED" ? "This investigation could not be completed." : (res.resolvedAnswer ?? ""),
            keyEvidence: evidence.slice(0, 3).map((e) => ({ source: e.source, label: e.title })),
            nextSteps: [] // structured next steps come with the LLM summary step
        }
    };
};
