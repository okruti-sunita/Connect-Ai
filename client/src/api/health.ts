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
