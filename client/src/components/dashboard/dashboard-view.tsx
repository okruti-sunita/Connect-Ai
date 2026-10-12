import {useEffect, useState} from "react";
import {useNavigate} from "react-router-dom";
import './style/dashboard.scss';
import {getHealth, type HealthDto} from "../../api/health";
import {toolsApi, type McpServerDto} from "../tools/tools-api";
import {findCatalogItem} from "../tools/tools-utils";
import {listInvestigations, STATUS_LABEL} from "../investigations_new/api";
import type {InvestigationSummary} from "../investigations_new/types";

/** Example questions per connected tool; each is answerable by that tool's MCP server. */
export const SUGGESTIONS_BY_TOOL: Record<string, string[]> = {
    github: [
        'Use the available GitHub MCP tools to identify my authenticated GitHub account and report the login.',
        'Which repositories do I own?',
        'Which repositories does each of my accounts own?',
        'Which open pull requests are there in my repositories?',
    ],
};

const DashboardView = () => {
    const navigate = useNavigate();
    const [question, setQuestion] = useState('');
    const [health, setHealth] = useState<HealthDto | null>(null);
    const [healthError, setHealthError] = useState<string | null>(null);
    const [tools, setTools] = useState<McpServerDto[]>([]);
    const [recent, setRecent] = useState<InvestigationSummary[]>([]);

    useEffect(() => {
        let alive = true;
        const load = async () => {
            const [h, t, r] = await Promise.allSettled([getHealth(), toolsApi.list(), listInvestigations({size: 5})]);
            if (!alive) return;
            if (h.status === 'fulfilled') {
                setHealth(h.value);
                setHealthError(null);
            } else {
                setHealthError(h.reason instanceof Error ? h.reason.message : 'Backend unreachable');
            }
            if (t.status === 'fulfilled') setTools(t.value);
            if (r.status === 'fulfilled') setRecent(r.value);
        };
        void load();
        // Connections may still be reconnecting right after a backend restart.
        const timer = setInterval(load, 5000);
        return () => {
            alive = false;
            clearInterval(timer);
        };
    }, []);

    // Opens the Investigations page and starts a run with this question
    const startInvestigation = (text = question) => {
        const trimmed = text.trim();
        if (!trimmed) return;
        navigate('/investigations', {state: {question: trimmed}});
    };

    const getCurrentGreeting = () => {
        const currentHour = new Date().getHours();
        if (currentHour < 12) return 'Good morning!';
        if (currentHour < 18) return 'Good afternoon!';
        return 'Good evening!';
    };

    const live = tools.filter((t) => t.runtimeConnected);
    const liveKinds = [...new Set(live.map((t) => findCatalogItem(t.name)?.id).filter((id): id is string => !!id))];
    const suggestions = liveKinds.flatMap((kind) => SUGGESTIONS_BY_TOOL[kind] ?? []);
    const llm = health?.llm;
    const modelReady = !!llm && (!llm.enabled || (llm.reachable !== false && llm.modelInstalled !== false));

    return (
        <div className="container">
            <div className="header">
                <h1 className="title">{getCurrentGreeting()}</h1>
                <p className="sub-title">What would you like to investigate?</p>

                {/* Real readiness: what the backend, the model and the MCP connections report right now. */}
                <div className="dash-status" role="status">
                    {healthError ? (
                        <span className="dash-chip bad"><i/>Backend unreachable: {healthError}</span>
                    ) : !health ? (
                        <span className="dash-chip"><i/>Checking status…</span>
                    ) : (
                        <>
                            <span className={`dash-chip ${modelReady ? 'ok' : 'bad'}`} title={llm?.message}>
                                <i/>{llm?.enabled ? `Model ${llm.model ?? llm.provider}: ${modelReady ? 'ready' : llm.message}` : 'LLM off (rule-based)'}
                            </span>
                            <span className={`dash-chip ${live.length > 0 ? 'ok' : 'warn'}`}>
                                <i/>{live.length} of {tools.length} connection{tools.length === 1 ? '' : 's'} live
                            </span>
                            {tools.filter((t) => !t.runtimeConnected).map((t) => (
                                <button key={t.id} type="button" className="dash-chip warn link" title={t.lastError ?? ''}
                                        onClick={() => navigate('/connections')}>
                                    <i/>{t.name}: {t.status === 'CONNECTING' ? 'connecting…' : 'not connected'}
                                </button>
                            ))}
                        </>
                    )}
                </div>
            </div>

            <div>
                <div className="suggestions-container">
                    {suggestions.length === 0 && (
                        <div className="suggestion-button" onClick={() => navigate('/connections')}>
                            Connect a tool (for example GitHub) to get started →
                        </div>
                    )}
                    {suggestions.map((suggestion) => (
                        <div key={suggestion} className="suggestion-button" onClick={() => setQuestion(suggestion)}>
                            {suggestion}
                        </div>
                    ))}
                </div>

                {/* Investigation Box */}
                <div className="investigation-container">
                    <textarea
                        className="investigation-textarea"
                        value={question}
                        aria-label="Investigation question"
                        onChange={(e) => setQuestion(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter' && !e.shiftKey) {
                                e.preventDefault();
                                startInvestigation();
                            }
                        }}
                        placeholder="Ask a question about your code, repositories, pull requests or engineering systems..."
                    />
                    <div className="investigation-divider"/>
                    <div className="investigation-footer">
                        <div className="tools-section">
                            <div className="tools-text">
                                {live.length > 0 ? 'Connect AI picks from your live tools:' : 'No live tools yet'}
                            </div>
                            <div className="tool-badges">
                                {live.map((t) => {
                                    const item = findCatalogItem(t.name);
                                    return (
                                        <span key={t.id} className={`tool-badge ${item?.id ?? ''}`} title={t.name}
                                              style={item ? {background: item.color, color: '#fff'} : undefined}>
                                            {item?.badge ?? t.name.slice(0, 2).toUpperCase()}
                                        </span>
                                    );
                                })}
                            </div>
                        </div>

                        <button
                            type="button"
                            className="investigate-button"
                            onClick={() => startInvestigation()}
                            disabled={!question.trim()}
                        >
                            Investigate
                        </button>
                    </div>
                </div>

                {recent.length > 0 && (
                    <div className="dash-recent">
                        <div className="dash-recent-head">
                            <span>Recent investigations</span>
                            <button type="button" onClick={() => navigate('/investigations')}>View all</button>
                        </div>
                        {recent.map((r) => (
                            <button key={r.id} type="button" className="dash-recent-row"
                                    onClick={() => navigate(`/investigations?id=${r.id}`)}>
                                <span className={`dash-pill ${r.status}`}>{STATUS_LABEL[r.status]}</span>
                                <span className="dash-recent-title">{r.title}</span>
                                <span className="dash-recent-ago">{r.updatedAgo}</span>
                            </button>
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
};

export default DashboardView;
