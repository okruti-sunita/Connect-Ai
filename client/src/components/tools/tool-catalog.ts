export type ToolCategory = 'Source Control' | 'Tickets' | 'Observability' | 'Chat' | 'Incident' | 'Custom';
export type ConnectMethod = 'OAUTH_APP' | 'OAUTH_DISCOVERY' | 'TOKEN_BEARER' | 'TOKEN_BASIC' | 'TOKEN_HEADER' | 'TOKEN_HEADERS' | 'NONE';
export type ConnectorStatus = 'AVAILABLE' | 'CONNECTED' | 'NEEDS_SIGN_IN' | 'SETUP_REQUIRED' | 'CONNECTION_ERROR';

export const CATEGORY_FILTERS: ToolCategory[] = ['Source Control', 'Tickets', 'Observability', 'Chat', 'Incident', 'Custom'];

export interface CatalogMethod {
    id: ConnectMethod;
    label: string;
    supported: boolean;
}

export interface CatalogField {
    name: string;
    label: string;
    type: 'text' | 'password' | 'select';
    required: boolean;
    placeholder?: string;
    helpText?: string;
    options?: {label: string; value: string}[];
}

export interface CatalogVariant {
    key: string;
    label: string;
    endpoint: string;
    verified: boolean;
}

/** Frontend representation of the server-owned catalog entry. */
export interface CatalogItem {
    id: string;
    key: string;
    name: string;
    description: string;
    category: ToolCategory;
    iconKey: string;
    methods: CatalogMethod[];
    defaultEndpoint: string;
    endpointEditable: boolean;
    endpointVerified: boolean;
    variants: CatalogVariant[];
    fields: CatalogField[];
    permissionsText: string;
    help: {steps: string[]; docsUrl: string};
    scopes: string[];
    oauthProvider?: string;
    connectionCount: number;
    status: ConnectorStatus;
    badge: string;
    color: string;
    logoUrl?: string;
    /** Compatibility aliases consumed by the existing wizard and detail view. */
    blurb: string;
    about: string;
    canDo: string[];
    requires: string[];
    defaultConnectionName: string;
    endpoint: string;
    deployments: {id: string; label: string; endpoint: string}[];
    tokenLabel: string;
    tokenHelp: string;
    localServerHelp?: string;
    connectorMode: 'managed-mcp' | 'local-mcp' | 'external-mcp-required';
}

export interface CatalogConnectorDefinition {
    key: string;
    name: string;
    description: string;
    category: ToolCategory;
    iconKey: string;
    methods: CatalogMethod[];
    defaultEndpoint: string;
    endpointEditable: boolean;
    endpointVerified: boolean;
    variants: CatalogVariant[];
    fields: CatalogField[];
    permissionsText: string;
    help: {steps: string[]; docsUrl: string};
    scopes: string[];
    oauthProvider?: string;
}

export interface CatalogApiEntry {
    connector: CatalogConnectorDefinition;
    connectionCount: number;
    status: ConnectorStatus;
    connections: CatalogConnectionSummary[];
}

export interface CatalogConnectionSummary {
    id: string;
    name: string;
    status: string;
    runtimeConnected: boolean;
    catalogKey: string | null;
    connectMethod: ConnectMethod | null;
}

const CATEGORY_COLORS: Record<ToolCategory, string> = {
    'Source Control': '#24292f', Tickets: '#2864dc', Observability: '#c2410c',
    Chat: '#7a3fe4', Incident: '#067a46', Custom: '#5b6178',
};

export function mapCatalogEntry(entry: CatalogApiEntry): CatalogItem {
    const connector = entry.connector;
    const connectorMode = connector.defaultEndpoint.startsWith('http://localhost')
        ? 'local-mcp'
        : connector.endpointVerified && connector.defaultEndpoint ? 'managed-mcp' : 'external-mcp-required';
    const passwordField = connector.fields.find((field) => field.type === 'password');
    return {
        ...connector,
        id: connector.key,
        connectionCount: entry.connectionCount,
        status: entry.status,
        badge: connector.name.slice(0, 2).toUpperCase(),
        color: CATEGORY_COLORS[connector.category] ?? '#475569',
        blurb: connector.description,
        about: connector.permissionsText,
        canDo: [],
        requires: connector.help.steps,
        defaultConnectionName: `${connector.name} - Main`,
        endpoint: connector.defaultEndpoint,
        deployments: connector.variants.map((variant) => ({id: variant.key, label: variant.label, endpoint: variant.endpoint})),
        tokenLabel: passwordField?.label ?? 'Access token',
        tokenHelp: passwordField?.helpText ?? 'Use the minimum permissions required.',
        ...(connectorMode === 'local-mcp' ? {localServerHelp: connector.permissionsText} : {}),
        connectorMode,
    };
}
