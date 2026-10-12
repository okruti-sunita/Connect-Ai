import { useEffect, useState } from "react";
import { message, Spin } from "antd";
import { Check, Download, Share2, Sparkles } from "lucide-react";
import "./style/investigations.scss";
import { getInvestigation } from "./api";
import EvidenceDrawer from "./EvidenceDrawer";
import SourceBadge from "./SourceBadge";
import ToolIcon from "./ToolIcon";
import { SOURCE_LABEL } from "./source-meta";
import type { EvidenceItem, InvestigationDetailData } from "./types";

interface Props {
    id: string;
    onBack: () => void;
}

const InvestigationDetail = ({ id, onBack }: Props) => {
    const [data, setData] = useState<InvestigationDetailData | null>(null);
    const [loading, setLoading] = useState(true);
    const [active, setActive] = useState<EvidenceItem | null>(null);

    /* Load investigation */
    useEffect(() => {
        let alive = true;
        setLoading(true);
        getInvestigation(id)
            .then((d) => alive && setData(d))
            .catch((e) => {
                console.error("Error while loading investigation:", e);
                message.error("Unable to load this investigation.");
            })
            .finally(() => alive && setLoading(false));
        return () => {
            alive = false;
        };
    }, [id]);


    if (loading || !data) {
        return (
            <div className="inv-page detail">
                <div className="crumbs">
                    <button type="button" onClick={onBack}>Investigations</button>
                </div>
                <div className="inv-loading">{loading ? <Spin size="large" /> : "Investigation not found."}</div>
            </div>
        );
    }

    // These are real execution steps returned by the backend.
    const finished = data.status !== "in-progress";

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
                            <span className={`status-pill ${data.status}`}>
                                {data.status === "in-progress" ? "In progress" : data.status.charAt(0).toUpperCase() + data.status.slice(1)}
                            </span>
                        </h1>
                        <p>Started {data.startedAgo} by {data.startedBy}</p>
                    </div>
                    <div className="detail-actions">
                        <button type="button" className="btn-outline"><Share2 size={15} /> Share</button>
                        <button type="button" className="btn-outline"><Download size={15} /> Export</button>
                    </div>
                </div>

                {/* Entity chips */}
                <div className="run-tags">
                    {data.tags.map((t) => (
                        <span key={t.label} className={`tag-chip ${t.accent ? "accent" : ""}`}>{t.label}</span>
                    ))}
                </div>

                {/* Tool steps */}
                {data.steps.length > 0 && (
                    <div className="run-steps">
                        {data.steps.map((step) => (
                            <div key={step.id} className="step-line">
                                <span className={`dot ${step.text.toLowerCase().includes("failed") ? "failed" : step.text.toLowerCase().includes("success") ? "done" : "pending"}`} />
                                <b>{SOURCE_LABEL[step.source]}</b>
                                <span>— {step.text}</span>
                            </div>
                        ))}
                    </div>
                )}

                {/* Results */}
                {finished ? (
                    <div className="run-results">
                        <h3>Evidence ({data.evidence.length})</h3>
                        {data.evidence.map((e) => (
                            <article key={e.id} className="run-card" onClick={() => setActive(e)}>
                                <SourceBadge source={e.source} />
                                <div className="run-card-body">
                                    <h4>{e.title} <small>· {e.when}</small></h4>
                                    <p>{e.snippet}</p>
                                </div>
                                <div className="relevance">
                                    <span>relevance</span>
                                    <b>{e.confidence}%</b>
                                    <div className="bar"><i style={{ width: `${e.confidence}%` }} /></div>
                                </div>
                            </article>
                        ))}

                        <div className="run-summary">
                            <strong><Sparkles size={15} /> Analysis</strong>
                            <p>{data.summary.text}</p>

                            <strong>Key evidence</strong>
                            <div className="key-evidence">
                                {data.summary.keyEvidence.map((k) => (
                                    <span key={k.label} className="ev-chip">
                                        <ToolIcon source={k.source} size={15} /> {k.label}
                                    </span>
                                ))}
                            </div>

                            <strong>Suggested next steps</strong>
                            <ul className="next-steps">
                                {data.summary.nextSteps.map((n) => <li key={n}><Check size={15} /> {n}</li>)}
                            </ul>
                        </div>
                    </div>
                ) : (
                    <div className="run-gathering"><Spin size="small" /> Investigation is still running. Results will appear when the API reports completion.</div>
                )}
            </div>

            <EvidenceDrawer item={active} onClose={() => setActive(null)} />
        </div>
    );
};

export default InvestigationDetail;
