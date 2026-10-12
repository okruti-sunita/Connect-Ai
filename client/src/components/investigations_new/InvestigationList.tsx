import { useState } from "react";
import { Alert, Button, Input, Select, Spin } from "antd";
import { ChevronRight, Search, Sparkles } from "lucide-react";
import "./style/investigations.scss";
import ToolIcon from "./ToolIcon";
import { STATUS_LABEL } from "./api";
import type { InvestigationSummary } from "./types";

interface Props {
    items: InvestigationSummary[];
    loading: boolean;
    loadError: string | null;
    starting: boolean;
    startError: string | null;
    onOpen: (item: InvestigationSummary) => void;
    onStart: (question: string) => void;
    onReload: () => void;
    /** Example questions answerable by the live connections. */
    examples?: string[];
}

const MAX_QUESTION = 2000;

const InvestigationList = ({ items, loading, loadError, starting, startError, onOpen, onStart, onReload, examples = [] }: Props) => {
    const [question, setQuestion] = useState("");
    const [statusFilter, setStatusFilter] = useState("all");
    const [search, setSearch] = useState("");

    const visible = items.filter(
        (i) =>
            (statusFilter === "all" || i.status === statusFilter) &&
            i.title.toLowerCase().includes(search.toLowerCase())
    );

    const trimmed = question.trim();
    const submit = () => {
        // The button is disabled while a submission is pending, so double clicks cannot start two runs.
        if (!trimmed || starting || trimmed.length > MAX_QUESTION) return;
        onStart(trimmed);
    };

    return (
        <div className="inv-page">
            <div className="inv-header">
                <div>
                    <h1>Investigations</h1>
                    <p>Ask a question. Connect AI picks tools from your connected MCP servers, runs them and answers from the real results.</p>
                </div>
            </div>

            <div className="inv-start-card">
                <h2>Start a new investigation</h2>
                <Input.TextArea
                    id="inv-question"
                    aria-label="Investigation question"
                    rows={3}
                    value={question}
                    maxLength={MAX_QUESTION}
                    disabled={starting}
                    onChange={(e) => setQuestion(e.target.value)}
                    onPressEnter={(e) => {
                        if (!e.shiftKey) {
                            e.preventDefault();
                            submit();
                        }
                    }}
                    placeholder="e.g. Use the available GitHub MCP tools to identify my authenticated GitHub account and report the login."
                />
                {examples.length > 0 && (
                    <div className="inv-examples" aria-label="Example questions">
                        {examples.map((example) => (
                            <button key={example} type="button" className="inv-example" disabled={starting}
                                    onClick={() => setQuestion(example)}>
                                {example}
                            </button>
                        ))}
                    </div>
                )}
                {startError && <Alert className="inv-start-error" type="error" showIcon title={startError} />}
                <div className="inv-start-actions">
                    <span className="inv-hint">Enter to submit · Shift+Enter for a new line</span>
                    <button type="button" className="btn-primary" onClick={submit} disabled={!trimmed || starting}>
                        <Sparkles size={16} /> {starting ? "Starting..." : "Investigate"}
                    </button>
                </div>
            </div>

            <div className="inv-list-head">
                <h2>Recent investigations</h2>
                <div className="inv-list-tools">
                    <Select
                        value={statusFilter}
                        onChange={setStatusFilter}
                        style={{ width: 140 }}
                        aria-label="Filter by status"
                        options={[
                            { value: "all", label: "All" },
                            { value: "resolved", label: "Answered" },
                            { value: "partial", label: "Partial" },
                            { value: "inconclusive", label: "Inconclusive" },
                            { value: "in-progress", label: "Running" },
                            { value: "failed", label: "Failed" },
                            { value: "archived", label: "Archived" }
                        ]}
                    />
                    <Input prefix={<Search size={15} />} placeholder="Search" value={search} aria-label="Search investigations"
                           onChange={(e) => setSearch(e.target.value)} style={{ width: 220 }} />
                </div>
            </div>

            <div className="inv-list">
                {loading && <div className="inv-empty"><Spin /></div>}
                {!loading && loadError && (
                    <Alert type="error" showIcon title={loadError}
                           action={<Button size="small" onClick={onReload}>Try again</Button>} />
                )}
                {!loading && !loadError && visible.length === 0 && (
                    <div className="inv-empty">
                        {items.length === 0 ? "No investigations yet. Ask a question above." : "No investigations match your filters."}
                    </div>
                )}
                {!loading && visible.map((i) => (
                    <button type="button" key={i.id} className="inv-row" onClick={() => onOpen(i)}>
                        <span className={`inv-status ${i.status}`}>
                            <span className="dot" />
                            {STATUS_LABEL[i.status]}
                        </span>
                        <span className="inv-title">
                            {i.route === 'FAST_PATH' && <span className="route-mini" title="Fast path: no AI model call">⚡</span>}
                            {i.title}
                        </span>
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
