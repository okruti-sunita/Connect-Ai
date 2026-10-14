// Talks to the MCP connection endpoints through the shared HTTP helper (base URL, errors).
import {apiRequest, ApiError} from '../../api/http';
import type {CatalogApiEntry, ConnectMethod} from './tool-catalog';

export type ConnectionStatus = 'REGISTERED' | 'CONNECTING' | 'CONNECTED' | 'REAUTH_REQUIRED' | 'FAILED' | 'DISCONNECTED';

/** Mirrors com.connectai.mcp.model.McpServer. The secret itself is never returned. */
export interface McpServerDto {
    id: string;
    name: string;
    transport: string;
    endpoint: string;
    authType: string;
    enabled: boolean;
    /** Persisted lifecycle state. */
    status: ConnectionStatus;
    lastError: string | null;
    lastConnectedAt: string | null;
    createdAt: string;
    updatedAt: string;
    /** Live truth: a handshaken MCP session exists in the backend right now. */
    runtimeConnected: boolean;
    /** BEARER: a token is stored. OAUTH: the user has signed in. */
    secretConfigured: boolean;
    /** OAuth provider id when authType is OAUTH. */
    oauthProvider: string | null;
    catalogKey: string | null;
    connectMethod: ConnectMethod | null;
}

/** Mirrors McpConnectionResponse. */
export interface ConnectionStatusDto {
    serverId: string;
    status: ConnectionStatus;
    initialized: boolean;
    protocolVersion: string | null;
    serverName: string | null;
    serverVersion: string | null;
    connectedAt: string | null;
    error: string | null;
}

export interface RegisterToolPayload {
    name: string;
    transport: 'STREAMABLE_HTTP';
    endpoint: string;
    authType: 'NONE' | 'BEARER' | 'OAUTH';
    secret: string | null;
    enabled: boolean;
    /** Required for OAUTH, e.g. "github". */
    oauthProvider?: string | null;
    catalogKey?: string | null;
}

export interface CreateConnectionPayload {
    catalogKey: string | null;
    name: string;
    variant?: string;
    endpoint: string;
    method: ConnectMethod;
    credentials: Record<string, string>;
}

export interface ConnectionTestDto {
    success: boolean;
    steps: {name: string; passed: boolean; code: string; message: string; hint: string | null}[];
    toolCount: number;
    toolNames: string[];
    code: string | null;
    message: string | null;
    hint: string | null;
}

export interface CreatedConnectionDto {
    connection: McpServerDto;
    toolCount: number;
    toolNames: string[];
}

export interface OAuthProviderDto {
    id: string;
    displayName: string;
    /** False until the server has the provider's OAuth client id and secret. */
    configured: boolean;
    /** Requested OAuth permissions, safe to display before sign-in. */
    scopes: string[];
    redirectUri: string;
}

export interface OAuthAttemptDto {
    id: string;
    status: 'PENDING' | 'CONNECTING' | 'SUCCEEDED' | 'FAILED';
    message: string;
}

export interface OAuthStartDto {
    attemptId: string;
    authorizationUrl: string;
    /** Browser origin of the configured backend callback page, used to validate its popup message. */
    callbackOrigin: string;
}

export interface OAuthCallbackPayload {
    state: string;
    code?: string;
    error?: string;
    errorDescription?: string;
}

export interface Capability {
    name: string;
    description: string;
}

/** Kept for existing imports; errors carry the server's own message. */
export {ApiError as ToolsApiError};

export const request = apiRequest;

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
    catalog: () => request<CatalogApiEntry[]>('/catalog'),
    testConnection: (payload: CreateConnectionPayload) => request<ConnectionTestDto>('/connections/test', 'POST', payload),
    createConnection: (payload: CreateConnectionPayload) => request<CreatedConnectionDto>('/connections', 'POST', payload),
    list: (signal?: AbortSignal) => request<McpServerDto[]>('/tools', 'GET', undefined, signal),
    register: (payload: RegisterToolPayload) => request<McpServerDto>('/tools', 'POST', payload),
    replaceBearerCredential: (id: string, secret: string) => request<McpServerDto>(
        `/tools/${id}/credential`, 'PUT', {secret}),
    replaceGrafanaCredentials: (id: string, payload: {grafanaUrl: string; serviceAccountToken: string; endpointToken?: string}) =>
        request<McpServerDto>(`/tools/${id}/grafana-credentials`, 'PUT', payload),
    connect: (id: string) => request<ConnectionStatusDto>(`/tools/${id}/connect`, 'POST'),
    disconnect: (id: string) => request<ConnectionStatusDto>(`/tools/${id}/disconnect`, 'POST'),
    oauthProviders: () => request<OAuthProviderDto[]>('/oauth/providers'),
    startOAuth: (id: string) => apiRequest<OAuthStartDto>(
        `/tools/${id}/oauth/start`, 'POST', undefined, undefined, 'include'),
    completeOAuth: (payload: OAuthCallbackPayload) => apiRequest<OAuthAttemptDto>(
        '/oauth/callback/complete', 'POST', payload, undefined, 'include'),
    oauthAttempt: (attemptId: string) => request<OAuthAttemptDto>(`/oauth/attempts/${encodeURIComponent(attemptId)}`),
    cancelOAuthAttempt: (attemptId: string) => request<OAuthAttemptDto>(
        `/oauth/attempts/${encodeURIComponent(attemptId)}/cancel`, 'POST'),
    /** Deletes the saved connection and its token. The backend answers 409 while it is still connected. */
    remove: (id: string) => request<void>(`/tools/${id}`, 'DELETE'),
    connection: (id: string, signal?: AbortSignal) =>
        request<ConnectionStatusDto>(`/tools/${id}/connection`, 'GET', undefined, signal),
    status: async (id: string, signal?: AbortSignal): Promise<ConnectionStatus> => {
        const data = await request<ConnectionStatusDto>(`/tools/${id}/connection`, 'GET', undefined, signal);
        return data?.initialized ? 'CONNECTED' : data?.status ?? 'REGISTERED';
    },
    capabilities: async (id: string, signal?: AbortSignal): Promise<Capability[]> =>
        normalizeCapabilities(await request<unknown>(`/tools/${id}/tools`, 'GET', undefined, signal)),
};
