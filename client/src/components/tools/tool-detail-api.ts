import {request, ToolsApiError} from './tools-api';
import type {Capability} from './tools-api';

export interface ParamInfo {
    name: string;
    type: string;
    required: boolean;
    description: string;
}

export interface CapabilityDetail extends Capability {
    params: ParamInfo[];
    schema: Record<string, unknown> | null;
    /** From MCP annotations when the server provides them, otherwise null. */
    readOnly: boolean | null;
    destructive: boolean | null;
}

export interface ExecutionOutcome {
    ok: boolean;
    text: string;
    ms: number;
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Accepts a bare array or {tools | capabilities | items: []}. Reads MCP fields: inputSchema and annotations. */
export function parseCapabilities(raw: unknown): CapabilityDetail[] {
    let list: unknown[] = [];
    if (Array.isArray(raw)) list = raw;
    else if (isRecord(raw)) {
        const key = ['tools', 'capabilities', 'items'].find((k) => Array.isArray(raw[k]));
        if (key) list = raw[key] as unknown[];
    }
    return list
        .filter(isRecord)
        .map((o) => {
            const schema = isRecord(o.inputSchema) ? o.inputSchema : isRecord(o.input_schema) ? o.input_schema : null;
            const annotations = isRecord(o.annotations) ? o.annotations : {};
            const props = schema && isRecord(schema.properties) ? schema.properties : {};
            const required = schema && Array.isArray(schema.required) ? schema.required.map(String) : [];
            return {
                name: String(o.name ?? ''),
                description: typeof o.description === 'string' ? o.description : '',
                schema,
                params: Object.entries(props).map(([name, def]) => {
                    const d = isRecord(def) ? def : {};
                    const type = typeof d.type === 'string' ? d.type : Array.isArray(d.type) ? d.type.join(' or ') : 'any';
                    return {name, type, required: required.includes(name), description: typeof d.description === 'string' ? d.description : ''};
                }),
                readOnly: typeof annotations.readOnlyHint === 'boolean' ? annotations.readOnlyHint : null,
                destructive: typeof annotations.destructiveHint === 'boolean' ? annotations.destructiveHint : null,
            };
        })
        .filter((c) => c.name.length > 0);
}

const WRITE_WORDS = /^(create|add|update|edit|delete|remove|merge|push|post|send|write|close|assign|fork|comment)/i;

/** Trust the server's annotations first. Without them, guess from the name and err on the side of caution. */
export function mayChangeData(c: CapabilityDetail): boolean {
    if (c.destructive === true) return true;
    if (c.readOnly !== null) return !c.readOnly;
    return WRITE_WORDS.test(c.name);
}

export function argumentSkeleton(c: CapabilityDetail): string {
    const out: Record<string, unknown> = {};
    for (const p of c.params) {
        if (!p.required) continue;
        out[p.name] = p.type === 'number' || p.type === 'integer' ? 0 : p.type === 'boolean' ? false : p.type === 'array' ? [] : p.type === 'object' ? {} : '';
    }
    return JSON.stringify(out, null, 2);
}

export function parseArguments(text: string): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
    try {
        const value: unknown = JSON.parse(text.trim() === '' ? '{}' : text);
        if (!isRecord(value)) return {ok: false, error: 'Arguments must be a JSON object, for example {"query": "login"}.'};
        return {ok: true, value};
    } catch {
        return {ok: false, error: 'Arguments are not valid JSON. Check commas and quotes.'};
    }
}

export async function fetchCapabilities(id: string, signal?: AbortSignal): Promise<{ items: CapabilityDetail[]; ms: number }> {
    const start = performance.now();
    const raw = await request<unknown>(`/tools/${id}/tools`, 'GET', undefined, signal);
    return {items: parseCapabilities(raw), ms: Math.round(performance.now() - start)};
}

/** If your McpToolExecutionRequest is shaped differently, this is the only place to change. */
const buildExecuteBody = (args: Record<string, unknown>) => ({arguments: args});

function extractText(raw: unknown): { text: string; isError: boolean } {
    if (typeof raw === 'string') return {text: raw, isError: false};
    if (isRecord(raw)) {
        const isError = raw.isError === true;
        if (Array.isArray(raw.content)) {
            const parts = raw.content.map((c) => (isRecord(c) && typeof c.text === 'string' ? c.text : JSON.stringify(c)));
            return {text: parts.join('\n'), isError};
        }
        return {text: JSON.stringify(raw, null, 2), isError};
    }
    return {text: raw === undefined ? '(no content returned)' : JSON.stringify(raw, null, 2), isError: false};
}

export async function executeTool(id: string, toolName: string, args: Record<string, unknown>, signal?: AbortSignal): Promise<ExecutionOutcome> {
    const start = performance.now();
    try {
        const raw = await request<unknown>(`/tools/${id}/tools/${encodeURIComponent(toolName)}/execute`, 'POST', buildExecuteBody(args), signal);
        const {text, isError} = extractText(raw);
        const shown = text.length > 6000 ? `${text.slice(0, 6000)}\n... (truncated)` : text;
        return {ok: !isError, text: shown, ms: Math.round(performance.now() - start)};
    } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') throw error;
        const message = error instanceof ToolsApiError ? error.message : 'The tool could not be run.';
        return {ok: false, text: message, ms: Math.round(performance.now() - start)};
    }
}
