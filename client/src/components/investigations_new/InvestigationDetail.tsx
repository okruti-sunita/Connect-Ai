import { useCallback, useEffect, useRef, useState } from "react";
import { Alert, Button, Spin } from "antd";
import { Sparkles } from "lucide-react";
import "./style/investigations.scss";
import { getInvestigation, STATUS_LABEL } from "./api";
import { errorText } from "../../api/http";
import EvidenceDrawer from "./EvidenceDrawer";
import AnswerText from "./AnswerText";
import SourceBadge from "./SourceBadge";
import ToolIcon from "./ToolIcon";
import { TERMINAL_STATUSES, type EvidenceItem, type InvestigationDetailData } from "./types";

interface Props {
    id: string;
    onBack: () => void;
}

const POLL_INTERVAL_MS = 2000;
/** Polling stops after this long even if the server never reports a terminal state. */
const MAX_POLL_MS = 15 * 60 * 1000;

const formatDuration = (ms: number | null) =>
    ms == null ? "" : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;

const InvestigationDetail = ({ id, onBack }: Props) => {
    const [data, setData] = useState<InvestigationDetailData | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [pollExpired, setPollExpired] = useState(false);
    const [active, setActive] = useState<EvidenceItem | null>(null);
    const [reloadKey, setReloadKey] = useState(0);
    const startedAt = useRef(Date.now());

    const isTerminal = (d: InvestigationDetailData | null) => !!d && TERMINAL_STATUSES.includes(d.status);

    /* Load, then poll while RUNNING. One timer at a time; cleaned up on unmount or id change. */
    useEffect(() => {
        let alive = true;
        let timer: ReturnType<typeof setTimeout> | undefined;
        startedAt.current = Date.now();
        setPollExpired(false);

        const tick = async () => {
            try {
                const d = await getInvestigation(id);
                if (!alive) return;
                setData(d);
                setError(null);
                if (!TERMINAL_STATUSES.includes(d.status)) {
                    if (Date.now() - startedAt.current > MAX_POLL_MS) {
                        setPollExpired(true);
                        return;
                    }
                    timer = setTimeout(tick, POLL_INTERVAL_MS);
                }
            } catch (e) {
                if (!alive) return;
                setError(errorText(e, "Unable to load this investigation."));
            } finally {
                if (alive) setLoading(false);
            }
        };

        setLoading(true);
        void tick();
        return () => {
            alive = false;
            if (timer) clearTimeout(timer);
        };
    }, [id, reloadKey]);

    const retry = useCallback(() => setReloadKey((k) => k + 1), []);

    if (!data) {
        return (
            <div className="inv-page detail">
                <div className="crumbs">
                    <button type="button" onClick={onBack}>Investigations</button>
                </div>
                <div className="inv-loading">
                    {loading ? <Spin size="large" /> : (
                        <Alert type="error" showIcon title={error ?? "Investigation not found."}
                               action={<Button size="small" onClick={retry}>Try again</Button>} />
                    )}
                </div>
            </div>
        );
    }

    const finished = isTerminal(data);
    const hasAnswer = data.status === "resolved" || data.status === "partial";

    return (
        <div className="inv-page detail">
            <div className="crumbs">
                <button type="button" onClick={onBack}>Investigations</button>
                <span>/</span>
                <span>{data.title}</span>
            </div>

            <div className="run-body">
                <div className="run-head">
                    <div>
                        <span className="run-kicker">Investigation</span>
                        <h1>
                            {data.title}
                            <span className={`status-pill ${data.status}`} role="status">{STATUS_LABEL[data.status]}</span>
                        </h1>
                        <p>
                            Started {data.startedAgo}
                            {data.completedAgo && <> · finished {data.completedAgo}</>}
                        </p>
                        {data.route && (
                            <span className={`route-badge ${data.route === 'FAST_PATH' ? 'fast' : 'ai'}`}
                                  title={data.route === 'FAST_PATH'
                                      ? 'Recognised question: a fixed recipe of read-only tool calls and an exact template answer. No AI model call.'
                                      : 'Planned and answered by the AI agent.'}>
                                {data.route === 'FAST_PATH' ? '⚡ Fast path' : '🧠 AI agent'}
                                {data.durationSeconds != null && <> · {data.durationSeconds < 10 ? data.durationSeconds.toFixed(1) : Math.round(data.durationSeconds)} s</>}
                                {data.route === 'FAST_PATH' && <> · no AI model call</>}
                            </span>
                        )}
                    </div>
                </div>

                {error && (
                    <Alert type="warning" showIcon title={`Could not refresh: ${error}`}
                           action={<Button size="small" onClick={retry}>Retry</Button>} />
                )}
                {pollExpired && (
                    <Alert type="warning" showIcon title="Still running after 15 minutes; automatic refresh stopped."
                           action={<Button size="small" onClick={retry}>Refresh</Button>} />
                )}

                {/* Real tool calls persisted by the backend, in execution order. */}
                <div className="run-steps" aria-live="polite">
                    {data.steps.length === 0 && !finished && (
                        <div className="step-line muted"><span className="dot running" /> Choosing a tool…</div>
                    )}
                    {data.steps.length === 0 && finished && (
                        <div className="step-line muted"><span className="dot" /> No tool was executed.</div>
                    )}
                    {data.steps.map((step) => (
                        <div key={step.id} className="step-block">
                            <div className="step-line">
                                <span className={`dot ${step.status === "SUCCESS" ? "done" : "failed"}`} />
                                <b>Step {step.stepNumber}: {step.toolName}</b>
                                {step.serverName && <span className="step-meta">on {step.serverName}</span>}
                                <span>{step.status === "SUCCESS" ? "succeeded" : "failed"}</span>
                                {step.durationMs != null && <span className="step-meta">{formatDuration(step.durationMs)}</span>}
                            </div>
                            {step.reason && <div className="step-detail">Why: {step.reason}</div>}
                            {step.argumentsJson && step.argumentsJson !== "{}" && (
                                <div className="step-detail mono">Arguments: {step.argumentsJson}</div>
                            )}
                            {step.errorMessage && <div className="step-detail error">Error: {step.errorMessage}</div>}
                        </div>
                    ))}
                    {!finished && data.steps.length > 0 && (
                        <div className="step-line muted"><span className="dot running" /> Working…</div>
                    )}
                </div>

                {finished ? (
                    <div className="run-results">
                        <div className={`run-summary ${data.status}`}>
                            <strong><Sparkles size={15} /> {hasAnswer ? "Answer" : data.status === "inconclusive" ? "No reliable answer" : "Investigation failed"}</strong>
                            {data.summary.text ? <AnswerText text={data.summary.text} /> : <p>The server returned no answer text.</p>}
                            {data.stopReason && <p className="stop-reason">Stopped because: {data.stopReason}</p>}
                            {data.summary.keyEvidence.length > 0 && (
                                <>
                                    <strong>Cited evidence</strong>
                                    <div className="key-evidence">
                                        {data.summary.keyEvidence.map((k) => (
                                            <span key={k.label} className="ev-chip">
                                                <ToolIcon source={k.source} size={15} /> {k.label}
                                            </span>
                                        ))}
                                    </div>
                                </>
                            )}
                        </div>

                        <h3>Evidence ({data.evidence.length})</h3>
                        {data.evidence.length === 0 && (
                            <p className="inv-empty-note">No tool returned usable evidence for this investigation.</p>
                        )}
                        {data.evidence.map((e) => (
                            <article key={e.id} className="run-card" tabIndex={0} role="button"
                                     onClick={() => setActive(e)}
                                     onKeyDown={(ev) => { if (ev.key === "Enter") setActive(e); }}>
                                <SourceBadge source={e.source} />
                                <div className="run-card-body">
                                    <h4>[{e.title}] {e.toolName ?? ""}{e.serverName && <small> · {e.serverName}</small>}{e.stepNumber != null && <small> · step {e.stepNumber}</small>} <small>· {e.when}</small></h4>
                                    <p className="evidence-snippet">{e.snippet}</p>
                                </div>
                                {e.confidence != null && (
                                    <div className="relevance">
                                        <span>relevance</span>
                                        <b>{e.confidence}%</b>
                                        <div className="bar"><i style={{ width: `${e.confidence}%` }} /></div>
                                    </div>
                                )}
                            </article>
                        ))}
                    </div>
                ) : (
                    <div className="run-gathering"><Spin size="small" /> Investigation is running. This page refreshes automatically.</div>
                )}
            </div>

            <EvidenceDrawer item={active} onClose={() => setActive(null)} />
        </div>
    );
};

export default InvestigationDetail;
