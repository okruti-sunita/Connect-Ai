export type ToolCategory = 'Source Control' | 'Tickets' | 'Observability' | 'Chat' | 'Incident' | 'Wiki' | 'Custom';

/** Chips shown above the catalog. Wiki tools are reachable under "All". */
export const CATEGORY_FILTERS: ToolCategory[] = ['Source Control', 'Tickets', 'Observability', 'Chat', 'Incident', 'Custom'];

export interface DeploymentOption {
    id: string;
    label: string;
    /** '' means the user must type the address (self-hosted or custom). */
    endpoint: string;
}

export interface CatalogItem {
    id: string;
    name: string;
    badge: string;
    color: string;
    /** Optional real logo. When absent a coloured badge is drawn. */
    logoUrl?: string;
    category: ToolCategory;
    blurb: string;
    about: string;
    canDo: string[];
    requires: string[];
    defaultConnectionName: string;
    /**
     * Default MCP server address. Leave '' until you have verified the vendor's URL:
     * the wizard then asks the user for it. Only GitHub is filled in here.
     */
    endpoint: string;
    deployments?: DeploymentOption[];
    tokenLabel: string;
    tokenHelp: string;
}

type EntryInput = Pick<CatalogItem, 'id' | 'name' | 'badge' | 'color' | 'category' | 'blurb' | 'canDo' | 'requires'> &
    Partial<CatalogItem>;

function entry(o: EntryInput): CatalogItem {
    return {
        defaultConnectionName: `${o.name} - Main`,
        endpoint: '',
        tokenLabel: 'Access token',
        tokenHelp: 'Use a token with read-only access.',
        about: `${o.blurb} Connect AI reads from it to build the context for your investigations.`,
        ...o,
    };
}

export const CATALOG: CatalogItem[] = [
    entry({
        id: 'github', name: 'GitHub', badge: 'GH', color: '#24292f', category: 'Source Control',
        blurb: 'Search code, review pull requests and inspect commits.',
        about: 'Connect to GitHub to search code, review PRs, inspect commits and issues.',
        canDo: ['Search code and repositories', 'View PRs and reviews', 'Inspect commits and diffs', 'Read issues and comments'],
        requires: ['Repo scope (read)', 'Issues scope (read)'],
        endpoint: 'https://api.githubcopilot.com/mcp/',
        deployments: [
            {id: 'cloud', label: 'Cloud (github.com)', endpoint: 'https://api.githubcopilot.com/mcp/'},
            {id: 'enterprise', label: 'GitHub Enterprise', endpoint: ''},
            {id: 'custom', label: 'Custom', endpoint: ''},
        ],
        tokenLabel: 'Personal Access Token',
        tokenHelp: 'Create a token with read access to repositories, issues and pull requests.',
    }),
    entry({
        id: 'jira', name: 'Jira', badge: 'JI', color: '#2864dc', category: 'Tickets',
        blurb: 'Find tickets, bugs and sprint history.',
        canDo: ['Find tickets by keyword or status', 'Read ticket details and comments', 'See sprint and project history'],
        requires: ['Read access to your projects'],
    }),
    entry({
        id: 'slack', name: 'Slack', badge: 'SL', color: '#7a3fe4', category: 'Chat',
        blurb: 'Search team discussions and decisions.',
        canDo: ['Search channels you can access', 'Read threads and replies', 'Link discussions to incidents'],
        requires: ['Access to the channels you share'],
    }),
    entry({
        id: 'grafana', name: 'Grafana', badge: 'GR', color: '#c2410c', category: 'Observability',
        blurb: 'Visualize and analyze your metrics.',
        canDo: ['Read dashboards and panels', 'See firing and past alerts', 'Query metrics and logs'],
        requires: ['A service account with Viewer role'],
    }),
    entry({
        id: 'splunk', name: 'Splunk', badge: 'SP', color: '#107c41', category: 'Observability',
        blurb: 'Analyze and monitor machine-generated data.',
        canDo: ['Run searches over your logs', 'Compare errors around a deployment', 'Find similar past errors'],
        requires: ['A token with search access'],
    }),
    entry({
        id: 'pagerduty', name: 'PagerDuty', badge: 'PD', color: '#067a46', category: 'Incident',
        blurb: 'Real-time operations and incident response.',
        canDo: ['Read incidents and timelines', 'See who was on call', 'Find related past incidents'],
        requires: ['A read-only API token'],
    }),
    entry({
        id: 'datadog', name: 'Datadog', badge: 'DD', color: '#632ca6', category: 'Observability',
        blurb: 'Cloud-scale monitoring and analytics.',
        canDo: ['Query metrics and logs', 'Read monitors and alerts', 'Inspect traces'],
        requires: ['An API key with read access'],
    }),
    entry({
        id: 'linear', name: 'Linear', badge: 'LN', color: '#4b4fd6', category: 'Tickets',
        blurb: 'Modern issue tracking for software teams.',
        canDo: ['Search issues and projects', 'Read issue history', 'See cycles and owners'],
        requires: ['A read-only API key'],
    }),
    entry({
        id: 'notion', name: 'Notion', badge: 'NO', color: '#2f2f2f', category: 'Wiki',
        blurb: 'All-in-one workspace for notes and projects.',
        canDo: ['Search pages and databases', 'Read runbooks and notes'],
        requires: ['Pages shared with the integration'],
    }),
    entry({
        id: 'confluence', name: 'Confluence', badge: 'CF', color: '#0b57d0', category: 'Wiki',
        blurb: 'Team collaboration and knowledge sharing.',
        canDo: ['Search pages and spaces', 'Read runbooks and design docs', 'Find who owns a service'],
        requires: ['Read access to your spaces'],
    }),
];

export const CUSTOM_ITEM: CatalogItem = entry({
    id: 'custom', name: 'Custom MCP Server', badge: '+', color: '#5b6178', category: 'Custom',
    blurb: 'Manually configure a connection.',
    about: 'Connect any server that speaks MCP, such as an internal tool. Connect AI discovers what it offers after connecting.',
    canDo: ['Connect an internal or less common tool', 'Capabilities are discovered after connecting'],
    requires: ['The server address from its owner', 'A token, if the server needs one'],
    defaultConnectionName: '',
    tokenLabel: 'Bearer token',
    tokenHelp: 'Sent securely with every request.',
});
