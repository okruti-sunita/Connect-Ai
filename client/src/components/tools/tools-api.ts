// Single place that talks to the backend. Change API_BASE if the context path changes.
const API_BASE = 'http://localhost:8080/connect-ai/api';

export type ConnectionStatus = 'REGISTERED' | 'CONNECTING' | 'CONNECTED' | 'FAILED' | 'DISCONNECTED';

export interface McpServerDto {
    id: string;
    name: string;
    transport: string;
    endpoint: string;
    authType: string;
    enabled: boolean;
    status: ConnectionStatus;
    lastError: string | null;
    lastConnectedAt: string | null;
    createdAt: string;
    updatedAt: string;
}

export interface RegisterToolPayload {
    name: string;
    transport: 'STREAMABLE_HTTP';
    endpoint: string;
    authType: 'NONE' | 'BEARER';
    secret: string | null;
    enabled: boolean;
}

export interface Capability {
    name: string;
    description: string;
}

export class ToolsApiError extends Error {
    readonly status: number;

    constructor(message: string, status: number) {
        super(message);
        this.name = 'ToolsApiError';
        this.status = status;
    }
}

function messageFor(status: number): string {
    if (status === 400) return 'Some of the details look invalid. Please check them and try again.';
    if (status === 404) return 'That connection no longer exists. Refresh and try again.';
    if (status === 409) return 'This conflicts with an existing connection. Try a different name.';
    return 'The server hit a problem. Please try again.';
}

export async function request<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
    let response: Response;
    try {
        const init: RequestInit = {
            method,
            signal,
            headers: {Accept: 'application/json', ...(body !== undefined ? {'Content-Type': 'application/json'} : {})},
        };
        if (body !== undefined) init.body = JSON.stringify(body);
        response = await fetch(`${API_BASE}${path}`, init);
    } catch (cause) {
        if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
        throw new ToolsApiError("Can't reach the Connect AI server. Check that the backend is running.", 0);
    }
    if (!response.ok) throw new ToolsApiError(messageFor(response.status), response.status);
    const text = await response.text();
    if (!text) return undefined as T;
    try {
        return JSON.parse(text) as T;
    } catch {
        throw new ToolsApiError('The server sent a response we could not read.', response.status);
    }
}

/** The discovery DTO shape is not confirmed, so accept a bare array or {tools|capabilities|items: []}. */
function normalizeCapabilities(raw: unknown): Capability[] {
    let list: unknown[] = [];
    if (Array.isArray(raw)) list = raw;
    else if (raw && typeof raw === 'object') {
        const record = raw as Record<string, unknown>;
        const key = ['tools', 'capabilities', 'items'].find((k) => Array.isArray(record[k]));
        if (key) list = record[key] as unknown[];
    }
    return list
        .map((item) => {
            const o = (item ?? {}) as Record<string, unknown>;
            return {name: String(o.name ?? ''), description: typeof o.description === 'string' ? o.description : ''};
        })
        .filter((c) => c.name.length > 0);
}

export const toolsApi = {
    list: (signal?: AbortSignal) => request<McpServerDto[]>('/tools', 'GET', undefined, signal),
    register: (payload: RegisterToolPayload) => request<McpServerDto>('/tools', 'POST', payload),
    connect: (id: string) => request<unknown>(`/tools/${id}/connect`, 'POST'),
    disconnect: (id: string) => request<unknown>(`/tools/${id}/disconnect`, 'POST'),
    status: async (id: string, signal?: AbortSignal): Promise<ConnectionStatus> => {
        const data = await request<{ status?: ConnectionStatus }>(`/tools/${id}/connection`, 'GET', undefined, signal);
        return data?.status ?? 'REGISTERED';
    },
    capabilities: async (id: string, signal?: AbortSignal): Promise<Capability[]> =>
        normalizeCapabilities(await request<unknown>(`/tools/${id}/tools`, 'GET', undefined, signal)),
};
