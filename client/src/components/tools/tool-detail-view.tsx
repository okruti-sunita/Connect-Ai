import {useEffect, useState} from 'react';
import {Button, Input, Switch, Tabs} from 'antd';
import {ArrowLeft, Search} from 'lucide-react';
import ToolLogo from './tool-logo';
import {toolsApi, type Capability, type McpServerDto} from './tools-api';
import {findCatalogItem, relativeTime, STATUS_LABEL} from './tools-utils';

interface ToolDetailViewProps {
    tool: McpServerDto;
    busy: boolean;
    onBack: () => void;
    onConnect: () => void;
    onDisconnect: () => void;
}

const ToolDetailView = ({tool, busy, onBack, onConnect, onDisconnect}: ToolDetailViewProps) => {
    const item = findCatalogItem(tool.name);
    const [capabilities, setCapabilities] = useState<Capability[] | null>(null);
    const [failed, setFailed] = useState(false);
    const [query, setQuery] = useState('');
    const [reloadKey, setReloadKey] = useState(0);
    const connected = tool.status === 'CONNECTED';

    useEffect(() => {
        if (!connected) {
            setCapabilities(null);
            return;
        }
        const controller = new AbortController();
        setFailed(false);
        toolsApi.capabilities(tool.id, controller.signal)
            .then(setCapabilities)
            .catch((error: unknown) => {
                if (!(error instanceof DOMException && error.name === 'AbortError')) setFailed(true);
            });
        return () => controller.abort();
    }, [tool.id, connected, reloadKey]);

    const visible = (capabilities ?? []).filter((c) => `${c.name} ${c.description}`.toLowerCase().includes(query.trim().toLowerCase()));

    const capabilitiesTab = !connected ? (
        <p className="empty-note">Connect this tool to discover what it can do.</p>
    ) : failed ? (
        <p className="empty-note">We could not load the capability list. <Button type="link" onClick={() => setReloadKey((k) => k + 1)}>Try again</Button></p>
    ) : capabilities === null ? (
        <p className="empty-note">Loading capabilities</p>
    ) : (
        <>
            <div className="cap-toolbar">
                <h3>MCP tools exposed ({capabilities.length})</h3>
                <Input allowClear prefix={<Search size={15}/>} placeholder="Search tools" value={query}
                       onChange={(e) => setQuery(e.target.value)} aria-label="Search capabilities"/>
            </div>
            <p className="hint-note">Turning individual capabilities on or off needs backend support and is not available yet.</p>
            {visible.length === 0 ? <p className="empty-note">No capability matches that search.</p> : visible.map((c) => (
                <div className="cap-row" key={c.name}>
                    <div>
                        <code>{c.name}</code>
                        {c.description && <p>{c.description}</p>}
                    </div>
                    <Switch checked disabled aria-label={`${c.name} is on`}/>
                </div>
            ))}
        </>
    );

    const details: [string, string][] = [
        ['Endpoint', tool.endpoint],
        ['Transport', tool.transport === 'STREAMABLE_HTTP' ? 'Streamable HTTP' : tool.transport],
        ['Authentication', tool.authType === 'BEARER' ? 'Bearer token (stored encrypted)' : 'None'],
    ];

    return (
        <div className="tools-page">
            <nav className="crumbs" aria-label="Breadcrumb">
                <button type="button" className="link-btn" onClick={onBack}><ArrowLeft size={15}/>Tools</button>
                <span aria-hidden="true">/</span>
                <span aria-current="page">{tool.name}</span>
            </nav>
            <header className="detail-head">
                <ToolLogo name={tool.name} item={item} size={64}/>
                <div className="detail-title">
                    <h1>{tool.name}</h1>
                    <p><span className={`status-dot ${tool.status}`}/>{STATUS_LABEL[tool.status]}
                        {connected && <> &middot; Last connected {relativeTime(tool.lastConnectedAt)}</>}</p>
                </div>
                <div className="detail-actions">
                    {connected ? (
                        <Button danger loading={busy} onClick={onDisconnect}>Disconnect</Button>
                    ) : (
                        <Button type="primary" loading={busy} onClick={onConnect}>
                            {tool.status === 'REGISTERED' ? 'Connect' : 'Reconnect'}
                        </Button>
                    )}
                </div>
            </header>
            {tool.status === 'FAILED' && (
                <p className="inline-alert" role="alert">The last connection attempt failed. Check the endpoint and token, then reconnect.</p>
            )}
            <div className="info-tiles">
                <div><span>Status</span><strong>{STATUS_LABEL[tool.status]}</strong></div>
                <div><span>Last connected</span><strong>{relativeTime(tool.lastConnectedAt)}</strong></div>
                <div><span>Capabilities exposed</span><strong>{capabilities ? capabilities.length : '-'}</strong></div>
                <div><span>Authentication</span><strong>{tool.authType === 'BEARER' ? 'Bearer token' : 'None'}</strong></div>
            </div>
            <Tabs
                className="detail-tabs"
                items={[
                    {key: 'capabilities', label: 'Capabilities', children: capabilitiesTab},
                    {
                        key: 'details', label: 'Details', children: (
                            <dl className="detail-list">
                                {details.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
                            </dl>
                        ),
                    },
                ]}
            />
        </div>
    );
};

export default ToolDetailView;
