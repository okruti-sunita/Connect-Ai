import { Drawer, Tabs } from "antd";
import { ExternalLink, Lightbulb, Pin } from "lucide-react";
import ToolIcon from "./ToolIcon";
import { SOURCE_LABEL } from "./source-meta";
import type { DiffLine, EvidenceItem } from "./types";

interface Props {
    item: EvidenceItem | null;
    onClose: () => void;
}

const DiffView = ({ lines }: { lines: DiffLine[] }) => (
    <div className="diff-view">
        {lines.map((l) => (
            <div key={l.line} className={`diff-line ${l.type}`}>
                <span className="ln">{l.line}</span>
                <span className="sign">{l.type === "add" ? "+" : l.type === "del" ? "-" : ""}</span>
                <code>{l.text}</code>
            </div>
        ))}
    </div>
);

const Empty = ({ label }: { label: string }) => (
    <p className="drawer-muted">{label} isn't available for this item yet.</p>
);

const EvidenceDrawer = ({ item, onClose }: Props) => {
    const d = item?.detail;

    return (
        <Drawer open={!!item} onClose={onClose} width={560} className="evidence-drawer" title={null}>
            {item && (
                <>
                    <div className="drawer-head">
                        <ToolIcon source={item.source} size={30} />
                        <div>
                            <h2>{item.title}</h2>
                            {d && <p>{d.subtitle}</p>}
                        </div>
                    </div>

                    <div className="drawer-pills">
                        <span className="pill blue">Confidence {item.confidence}%</span>
                        {d && <span className="pill blue">Why this matters: {d.whyItMatters}</span>}
                        <span className="pill">Source: {SOURCE_LABEL[item.source]}</span>
                    </div>

                    <Tabs
                        items={[
                            {
                                key: "overview",
                                label: "Overview",
                                children: d ? (
                                    <>
                                        <h4>Description</h4>
                                        <div className="drawer-box mono">{d.description}</div>

                                        <h4>Key changes</h4>
                                        <ul className="drawer-list">
                                            {d.keyChanges.map((k) => <li key={k}>{k}</li>)}
                                        </ul>

                                        <h4>Diff preview</h4>
                                        <DiffView lines={d.diff} />

                                        <div className="reasoning">
                                            <Lightbulb size={18} />
                                            <div>
                                                <strong>Reasoning</strong>
                                                <p>{d.reasoning}</p>
                                            </div>
                                        </div>
                                    </>
                                ) : (
                                    <>
                                        <h4>Summary</h4>
                                        <div className="drawer-box">{item.snippet}</div>
                                    </>
                                )
                            },
                            { key: "diff", label: "Diff", children: d ? <DiffView lines={d.diff} /> : <Empty label="Diff" /> },
                            { key: "reviewers", label: "Reviewers", children: <Empty label="Reviewer data" /> },
                            { key: "linked", label: "Linked Items", children: <Empty label="Linked items" /> }
                        ]}
                    />

                    <div className="drawer-footer">
                        {item.url && (
                            <a className="btn-primary" href={item.url} target="_blank" rel="noreferrer">
                                Open in {SOURCE_LABEL[item.source]} <ExternalLink size={15} />
                            </a>
                        )}
                        <button type="button" className="btn-outline">
                            <Pin size={15} /> Pin to answer
                        </button>
                    </div>
                </>
            )}
        </Drawer>
    );
};

export default EvidenceDrawer;
