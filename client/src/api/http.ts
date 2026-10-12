/*
 * Shared HTTP helper for every backend call.
 *
 * - The base URL comes from VITE_API_BASE_URL. The default '/connect-ai/api' is served by
 *   the Vite dev proxy (vite.config.ts), so the browser never needs CORS in development.
 * - fetch() only rejects on network failure, so non-2xx responses are checked explicitly
 *   and turned into ApiError with the server's own message (Spring's {"message": ...})
 *   whenever one is available, falling back to a status-specific explanation.
 */
export const API_BASE = (
    (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/connect-ai/api'
).replace(/\/+$/, '');

export class ApiError extends Error {
    readonly status: number;

    constructor(message: string, status: number) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
    }

    /** True when the server could not be reached at all. */
    get isNetwork(): boolean {
        return this.status === 0;
    }
}

function fallbackMessage(status: number): string {
    if (status === 400) return 'The request was rejected as invalid.';
    if (status === 404) return 'That item no longer exists. Refresh and try again.';
    if (status === 409) return 'The request conflicts with the current state. Refresh and try again.';
    if (status === 502) return 'An upstream service (MCP server or model) failed.';
    if (status >= 500) return 'The server hit a problem. Please try again.';
    return `Request failed with status ${status}.`;
}

async function errorMessage(response: Response): Promise<string> {
    try {
        const text = await response.text();
        if (text) {
            const body = JSON.parse(text) as { message?: unknown; detail?: unknown; error?: unknown };
            const message = [body.message, body.detail].find((m) => typeof m === 'string' && m.trim().length > 0);
            if (typeof message === 'string') return message;
        }
    } catch {
        // Non-JSON error body (proxy page, HTML): use the status-based explanation.
    }
    return fallbackMessage(response.status);
}

export async function apiRequest<T>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<T> {
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
        throw new ApiError("Can't reach the Connect AI server. Check that the backend is running.", 0);
    }
    if (!response.ok) throw new ApiError(await errorMessage(response), response.status);
    const text = await response.text();
    if (!text) return undefined as T;
    try {
        return JSON.parse(text) as T;
    } catch {
        throw new ApiError('The server sent a response that could not be read.', response.status);
    }
}

export const errorText = (error: unknown, fallback = 'Something went wrong.'): string =>
    error instanceof Error && error.message ? error.message : fallback;
