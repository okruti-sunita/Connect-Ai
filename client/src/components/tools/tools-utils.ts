import {CATALOG, type CatalogItem} from './tool-catalog';
import type {ConnectionStatus} from './tools-api';

export const STATUS_LABEL: Record<ConnectionStatus, string> = {
    REGISTERED: 'Not connected',
    CONNECTING: 'Connecting',
    CONNECTED: 'Connected',
    FAILED: 'Failed',
    DISCONNECTED: 'Disconnected',
};

export function relativeTime(iso?: string | null): string {
    if (!iso) return 'never';
    const then = new Date(iso).getTime();
    if (Number.isNaN(then)) return 'unknown';
    const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
    if (seconds < 60) return 'just now';
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
}

/** The backend does not store which catalog entry a tool came from, so match on the name. */
export function findCatalogItem(toolName: string): CatalogItem | undefined {
    const name = toolName.toLowerCase();
    return CATALOG.find((c) => name.includes(c.id) || name.includes(c.name.toLowerCase()));
}
