import { Drawer } from "antd";
import { ExternalLink } from "lucide-react";
import ToolIcon from "./ToolIcon";
import { SOURCE_LABEL } from "./source-meta";
import type { EvidenceItem } from "./types";

interface Props {
    item: EvidenceItem | null;
    onClose: () => void;
}

/** Shows one evidence record exactly as the tool returned it, with its provenance. */
const EvidenceDrawer = ({ item, onClose }: Props) => (
    <Drawer open={!!item} onClose={onClose} size={560} className="evidence-drawer" title={null}>
        {item && (
            <>
                <div className="drawer-head">
                    <ToolIcon source={item.source} size={30} />
                    <div>
                        <h2>[{item.title}] {item.toolName ?? ""}</h2>
                        <p>{item.serverName ?? SOURCE_LABEL[item.source]}{item.stepNumber != null && <> · step {item.stepNumber}</>} · {item.when}</p>
                    </div>
                </div>

                <div className="drawer-pills">
                    <span className="pill">Source: {SOURCE_LABEL[item.source]}</span>
                    {item.confidence != null && <span className="pill blue">Confidence {item.confidence}%</span>}
                </div>

                <h4>Tool output</h4>
                <pre className="drawer-box mono evidence-raw">{item.snippet}</pre>

                {item.url && (
                    <div className="drawer-footer">
                        <a className="btn-primary" href={item.url} target="_blank" rel="noreferrer">
                            Open in {SOURCE_LABEL[item.source]} <ExternalLink size={15} />
                        </a>
                    </div>
                )}
            </>
        )}
    </Drawer>
);

export default EvidenceDrawer;
