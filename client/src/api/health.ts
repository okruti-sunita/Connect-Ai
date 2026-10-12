import {apiRequest} from './http';

/** Mirrors com.connectai.api.HealthController.Health. */
export interface HealthDto {
    backend: string;
    toolsMode: string;
    llm: {
        enabled: boolean;
        provider: string;
        model: string | null;
        reachable: boolean | null;
        modelInstalled: boolean | null;
        message: string;
    };
    mcpServers: { id: string; name: string; persistedStatus: string; live: boolean; lastError: string | null }[];
}

export const getHealth = () => apiRequest<HealthDto>('/health');

/** Mirrors com.connectai.api.InsightsController.Efficiency (measured, not estimated). */
export interface RouteStatsDto {
    runs: number;
    medianSeconds: number | null;
    p90Seconds: number | null;
    answered: number;
}

export interface EfficiencyDto {
    completedRuns: number;
    routes: Record<string, RouteStatsDto>;
    fastPathShare: number | null;
    speedup: number | null;
    outcomes: Record<string, number>;
    honestNonAnswers: number;
}

export const getEfficiency = () => apiRequest<EfficiencyDto>('/insights/efficiency');
