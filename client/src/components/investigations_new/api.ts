/*
 * ---------------------------------------------------------------
 * Investigations API layer
 *
 * Every function below currently returns sample data so the UI
 * works. To go live: uncomment the fetch code in each function,
 * delete the "TEMP" block under it, then delete mock-data.ts.
 * Components never call fetch directly, so nothing else changes.
 * ---------------------------------------------------------------
 */
import { buildDetail, INVESTIGATIONS } from "./mock-data";
import type { InvestigationDetailData, InvestigationSummary } from "./types";

// const API_BASE_URL = "http://localhost:8080/connect-ai/api/investigations";

/* TEMP: in-memory store so "Investigate" works without a backend */
const store: InvestigationSummary[] = [...INVESTIGATIONS];

/* GET /api/investigations */
export const listInvestigations = async (): Promise<InvestigationSummary[]> => {
    // const res = await fetch(API_BASE_URL);
    // if (!res.ok) throw new Error(`API failed with status: ${res.status}`);
    // return res.json();

    return [...store]; // TEMP
};

/* POST /api/investigations   body: { question } */
export const startInvestigation = async (
    question: string
): Promise<InvestigationSummary> => {
    // const res = await fetch(API_BASE_URL, {
    //     method: "POST",
    //     headers: { "Content-Type": "application/json" },
    //     body: JSON.stringify({ question })
    // });
    // if (!res.ok) throw new Error(`API failed with status: ${res.status}`);
    // return res.json();

    // TEMP
    const created: InvestigationSummary = {
        id: `inv-${Date.now()}`,
        title: question,
        status: "in-progress",
        sources: ["github", "jira", "grafana"],
        owner: "SG",
        updatedAgo: "just now"
    };
    store.unshift(created);
    return created;
};

/* GET /api/investigations/{id}  (timeline + messages + evidence) */
export const getInvestigation = async (
    id: string
): Promise<InvestigationDetailData> => {
    // const res = await fetch(`${API_BASE_URL}/${id}`);
    // if (!res.ok) throw new Error(`API failed with status: ${res.status}`);
    // return res.json();

    // TEMP
    const found = store.find((i) => i.id === id);
    if (!found) throw new Error("Investigation not found");
    return buildDetail(found);
};
