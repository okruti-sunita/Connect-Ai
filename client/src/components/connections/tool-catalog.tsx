import type { ReactNode } from "react";
import GithubMark from "../shared/GithubMark";
import {
    MessageSquare,
    Activity,
    Siren,
    BookOpen,
    Ticket,
    Search,
    Database
} from "lucide-react";

export const API_BASE_URL = "http://localhost:8080/connect-ai/api/tools";

export type ToolCategory =
    | "Source Control"
    | "Tickets"
    | "Observability"
    | "Chat"
    | "Incident"
    | "Wiki";

export interface CatalogTool {
    key: string;
    name: string;
    subtitle: string;
    description: string;
    category: ToolCategory;
    endpoint?: string;
    icon: ReactNode;
    highlights?: string[];
    requires?: string[];
}

export const GITHUB_CLOUD_URL = "https://api.githubcopilot.com/mcp/";

export const TOOL_CATALOG: CatalogTool[] = [
    {
        key: "github",
        name: "GitHub",
        subtitle: "Official GitHub MCP Server",
        description:
            "Connect to GitHub to search code, review PRs, inspect commits and issues.",
        category: "Source Control",
        endpoint: GITHUB_CLOUD_URL,
        icon: <GithubMark size={24} />,
        highlights: [
            "Search code and repositories",
            "View PRs and reviews",
            "Inspect commits and diffs",
            "Read issues and comments"
        ],
        requires: ["Repo scope (read)", "Issues scope (read)"]
    },
    {
        key: "jira",
        name: "Jira",
        subtitle: "Jira MCP Server",
        description: "Work items, sprints and project tracking.",
        category: "Tickets",
        icon: <Ticket size={24} />
    },
    {
        key: "slack",
        name: "Slack",
        subtitle: "Slack MCP Server",
        description: "Engineering discussions and decisions.",
        category: "Chat",
        icon: <MessageSquare size={24} />
    },
    {
        key: "grafana",
        name: "Grafana",
        subtitle: "Grafana MCP Server",
        description: "Visualize and analyze your metrics.",
        category: "Observability",
        icon: <Activity size={24} />
    },
    {
        key: "splunk",
        name: "Splunk",
        subtitle: "Splunk MCP Server",
        description: "Analyze and monitor machine-generated data.",
        category: "Observability",
        icon: <Search size={24} />
    },
    {
        key: "pagerduty",
        name: "PagerDuty",
        subtitle: "PagerDuty MCP Server",
        description: "Real-time operations and incident response.",
        category: "Incident",
        icon: <Siren size={24} />
    },
    {
        key: "datadog",
        name: "Datadog",
        subtitle: "Datadog MCP Server",
        description: "Cloud-scale monitoring and analytics.",
        category: "Observability",
        icon: <Database size={24} />
    },
    {
        key: "confluence",
        name: "Confluence",
        subtitle: "Confluence MCP Server",
        description: "Team collaboration and knowledge sharing.",
        category: "Wiki",
        icon: <BookOpen size={24} />
    }
];

export const CATEGORIES: ("All" | ToolCategory | "Custom")[] = [
    "All",
    "Source Control",
    "Tickets",
    "Observability",
    "Chat",
    "Incident",
    "Wiki",
    "Custom"
];
