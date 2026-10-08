import {useState} from 'react';
import {Button, Input, Switch} from 'antd';
import {ChevronDown, CircleAlert, FileText, GitPullRequest, List, Play, Search, Wrench} from 'lucide-react';
import {mayChangeData, type CapabilityDetail} from './tool-detail-api';

function iconFor(name: string) {
    const n = name.toLowerCase();
    if (/pull|merge|branch/.test(n)) return GitPullRequest;
    if (/issue|ticket|alert|incident/.test(n)) return CircleAlert;
    if (/search|find|query/.test(n)) return Search;
    if (/list|history|log/.test(n)) return List;
    if (/file|page|doc|read|get/.test(n)) return FileText;
    return Wrench;
}

interface CapabilityListProps {
    items: CapabilityDetail[];
    onTry: (name: string) => void;
}

const CapabilityList = ({items, onTry}: CapabilityListProps) => {
    const [query, setQuery] = useState('');
    const [open, setOpen] = useState<string | null>(null);
    const q = query.trim().toLowerCase();
    const visible = items.filter((c) => `${c.name} ${c.description}`.toLowerCase().includes(q));

    return (
        <>
            <div className="cap-toolbar">
                <h3>MCP tools exposed ({items.length})</h3>
                <Input allowClear prefix={<Search size={15}/>} placeholder="Search tools" value={query}
                       onChange={(e) => setQuery(e.target.value)} aria-label="Search capabilities"/>
            </div>
            <p className="hint-note">Turning individual tools on or off needs backend support and is not available yet.</p>
            {visible.length === 0 && <p className="empty-note">No tool matches that search.</p>}
            {visible.map((c) => {
                const Icon = iconFor(c.name);
                const expanded = open === c.name;
                const risky = mayChangeData(c);
                return (
                    <article className="cap-item" key={c.name}>
                        <div className="cap-main">
                            <span className="cap-icon" aria-hidden="true"><Icon size={18}/></span>
                            <div className="cap-text">
                                <code>{c.name}</code>{' '}
                                {risky && <span className="pill warn">May change data</span>}
                                {c.description && <p>{c.description}</p>}
                            </div>
                            <Button size="small" icon={<Play size={13}/>} onClick={() => onTry(c.name)} aria-label={`Try ${c.name}`}>Try</Button>
                            <Switch checked disabled aria-label={`${c.name} is on`}/>
                        </div>
                        {c.params.length > 0 && (
                            <>
                                <button type="button" className="link-btn params-toggle" aria-expanded={expanded}
                                        onClick={() => setOpen(expanded ? null : c.name)}>
                                    <ChevronDown size={14} className={expanded ? 'flip' : ''}/>
                                    {c.params.length} {c.params.length === 1 ? 'parameter' : 'parameters'}
                                </button>
                                {expanded && (
                                    <ul className="param-list">
                                        {c.params.map((p) => (
                                            <li key={p.name}>
                                                <code>{p.name}</code> <span className="mut">{p.type}{p.required ? ', required' : ''}</span>
                                                {p.description && <div className="mut">{p.description}</div>}
                                            </li>
                                        ))}
                                    </ul>
                                )}
                            </>
                        )}
                    </article>
                );
            })}
        </>
    );
};

export default CapabilityList;
