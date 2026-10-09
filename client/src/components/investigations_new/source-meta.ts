import type { ToolSource } from "./types";

export const SOURCE_LABEL: Record<ToolSource, string> = {
    github: "GitHub",
    jira: "Jira",
    grafana: "Grafana",
    splunk: "Splunk",
    kafka: "Kafka",
    slack: "Slack"
};

export const SOURCE_ABBR: Record<ToolSource, string> = {
    github: "GH",
    jira: "JI",
    grafana: "GR",
    splunk: "SP",
    kafka: "KA",
    slack: "SL"
};
