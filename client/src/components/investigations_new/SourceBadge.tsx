import { SOURCE_ABBR } from "./source-meta";
import type { ToolSource } from "./types";

const SourceBadge = ({ source }: { source: ToolSource }) => (
    <span className={`src-badge ${source}`}>{SOURCE_ABBR[source]}</span>
);

export default SourceBadge;
