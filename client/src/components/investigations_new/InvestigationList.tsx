import { useState } from "react";
import { Input, Select, Spin } from "antd";
import { ChevronRight, Search, Sparkles } from "lucide-react";
import "./style/investigations.scss";
import ToolIcon from "./ToolIcon";
import type { InvestigationSummary } from "./types";

interface Props {
    items: InvestigationSummary[];
    loading: boolean;
    starting: boolean;
    onOpen: (item: InvestigationSummary) => void;
    onStart: (question: string) => void;
}

const QUICK_ACTIONS = ["Attach a Jira ticket", "Paste a Grafana alert", "Link a PR", "Paste a Splunk query"];

const InvestigationList = ({ items, loading, starting, onOpen, onStart }: Props) => {
    const [question, setQuestion] = useState("");
    const [statusFilter, setStatusFilter] = useState("all");
    const [search, setSearch] = useState("");

    const visible = items.filter(
        (i) =>
            (statusFilter === "all" || i.status === statusFilter) &&
            i.title.toLowerCase().includes(search.toLowerCase())
    );

    const submit = () => {
        if (!question.trim()) return;
        onStart(question.trim());
        setQuestion("");
    };

    return (
        <div className="inv-page">
            <div className="inv-header">
                <div>
                    <h1>Investigations</h1>
                    <p>Ask a question, paste an alert, or link a ticket. Connect AI will gather evidence from your connected tools.</p>
                </div>
                {/*<button type="button" className="btn-outline" onClick={() => document.getElementById("inv-question")?.focus()}>*/}
                {/*    New investigation*/}
                {/*</button>*/}
            </div>

            {/*<div className="inv-start-card">*/}
            {/*    <h2>Start a new investigation</h2>*/}
            {/*    <Input.TextArea*/}
            {/*        id="inv-question"*/}
            {/*        rows={3}*/}
            {/*        value={question}*/}
            {/*        onChange={(e) => setQuestion(e.target.value)}*/}
            {/*        placeholder="e.g. Why is checkout latency spiking in prod since 10am?"*/}
            {/*    />*/}
            {/*    <div className="inv-start-actions">*/}
            {/*        <div className="quick-chips">*/}
            {/*            {QUICK_ACTIONS.map((a) => (*/}
            {/*                <button key={a} type="button" onClick={() => setQuestion((q) => (q ? `${q}\n${a}: ` : `${a}: `))}>*/}
            {/*                    {a}*/}
            {/*                </button>*/}
            {/*            ))}*/}
            {/*        </div>*/}
            {/*        <button type="button" className="btn-primary" onClick={submit} disabled={!question.trim() || starting}>*/}
            {/*            <Sparkles size={16} /> {starting ? "Starting..." : "Investigate"}*/}
            {/*        </button>*/}
            {/*    </div>*/}
            {/*</div>*/}

            {/*<div className="inv-filters">*/}
            {/*    <Select defaultValue="all" options={[{ value: "all", label: "Scope: All connected tools" }]} />*/}
            {/*    <Select defaultValue="24h" options={[*/}
            {/*        { value: "1h", label: "Time window: Last 1h" },*/}
            {/*        { value: "24h", label: "Time window: Last 24h" },*/}
            {/*        { value: "7d", label: "Time window: Last 7d" }*/}
            {/*    ]} />*/}
            {/*</div>*/}

            <div className="inv-list-head">
                <h2>Recent investigations</h2>
                <div className="inv-list-tools">
                    <Select
                        value={statusFilter}
                        onChange={setStatusFilter}
                        style={{ width: 130 }}
                        options={[
                            { value: "all", label: "All" },
                            { value: "resolved", label: "Resolved" },
                            { value: "in-progress", label: "In progress" },
                            { value: "archived", label: "Archived" }
                        ]}
                    />
                    <Input prefix={<Search size={15} />} placeholder="Search" value={search}
                           onChange={(e) => setSearch(e.target.value)} style={{ width: 220 }} />
                </div>
            </div>

            <div className="inv-list">
                {loading && <div className="inv-empty"><Spin /></div>}
                {!loading && visible.length === 0 && <div className="inv-empty">No investigations match your filters.</div>}
                {!loading && visible.map((i) => (
                    <button type="button" key={i.id} className="inv-row" onClick={() => onOpen(i)}>
                        <span className={`inv-status ${i.status}`}>
                            <span className="dot" />
                            {i.status}
                        </span>
                        <span className="inv-title">{i.title}</span>
                        <span className="inv-sources">
                            {i.sources.map((s) => <ToolIcon key={s} source={s} />)}
                        </span>
                        <span className="avatar">{i.owner}</span>
                        <span className="inv-ago">{i.updatedAgo}</span>
                        <ChevronRight size={16} />
                    </button>
                ))}
            </div>
        </div>
    );
};

export default InvestigationList;
