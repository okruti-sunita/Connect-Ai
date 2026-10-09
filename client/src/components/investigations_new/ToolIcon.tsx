import type { ReactNode } from "react";
import { Diamond, Flame, MessageSquare, Radio, ScrollText } from "lucide-react";
import GithubMark from "../shared/GithubMark";
import type { ToolSource } from "./types";

const META: Record<ToolSource, { color: string; render: (s: number) => ReactNode }> = {
    github: { color: "#111827", render: (s) => <GithubMark size={s} /> },
    jira: { color: "#2563eb", render: (s) => <Diamond size={s} fill="currentColor" /> },
    grafana: { color: "#f97316", render: (s) => <Flame size={s} /> },
    splunk: { color: "#16a34a", render: (s) => <ScrollText size={s} /> },
    kafka: { color: "#0d9488", render: (s) => <Radio size={s} /> },
    slack: { color: "#7c3aed", render: (s) => <MessageSquare size={s} /> }
};

const ToolIcon = ({ source, size = 18 }: { source: ToolSource; size?: number }) => (
    <span className="tool-icon-inline" style={{ color: META[source].color }}>
        {META[source].render(size)}
    </span>
);

export default ToolIcon;
