import {useEffect, useRef, useState} from 'react';
import {Button, Tabs} from 'antd';
import {ArrowLeft} from 'lucide-react';
import './style/tool-detail.scss';
import CapabilityList from './capability-list';
import ToolTestPanel from './tool-test-panel';
import ToolLogo from './tool-logo';
import {fetchCapabilities, mayChangeData, type CapabilityDetail} from './tool-detail-api';
import type {McpServerDto} from './tools-api';
import {findCatalogItem, relativeTime, STATUS_LABEL} from './tools-utils';

interface ToolDetailPageProps {
    tool: McpServerDto;
    busy: boolean;
    onBack: () => void;
    onConnect: () => void;
    onDisconnect: () => void;
}

function hostOf(endpoint: string): string {
    try {
        return new URL(endpoint).hostname;
    } catch {
        return '';
    }
}

const ToolDetailPage = ({tool, busy, onBack, onConnect, onDisconnect}: ToolDetailPageProps) => {
    const item = findCatalogItem(tool.name);
    const connected = tool.status === 'CONNECTED';
    const [items, setItems] = useState<CapabilityDetail[] | null>(null);
    const [ms, setMs] = useState<number | null>(null);
    const [failed, setFailed] = useState(false);
    const [reloadKey, setReloadKey] = useState(0);
    const [selected, setSelected] = useState<string | null>(null);
    const panelRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!connected) {
            setItems(null);
            setMs(null);
            return;
        }
        const controller = new AbortController();
        setFailed(false);
        fetchCapabilities(tool.id, controller.signal)
            .then(({items: list, ms: elapsed}) => {
                setItems(list);
                setMs(elapsed);
                // Start the test panel on a tool that cannot change data, when one exists.
                setSelected((current) => current ?? (list.find((c) => !mayChangeData(c)) ?? list[0])?.name ?? null);
            })
            .catch((error: unknown) => {
                if (!(error instanceof DOMException && error.name === 'AbortError')) setFailed(true);
            });
        return () => controller.abort();
    }, [tool.id, connected, reloadKey]);

    const tryTool = (name: string) => {
        setSelected(name);
        panelRef.current?.scrollIntoView?.({behavior: 'smooth', block: 'start'});
    };

    const host = hostOf(tool.endpoint);
    const auth = tool.authType === 'BEARER' ? 'Bearer token' : 'None';

    const capabilitiesTab = !connected ? (
        <p className="empty-note">Connect this tool to see what it can do.</p>
    ) : failed ? (
        <p className="empty-note">We could not load the tool list. <Button type="link" onClick={() => setReloadKey((k) => k + 1)}>Try again</Button></p>
    ) : items === null ? (
        <p className="empty-note">Loading tools</p>
    ) : items.length === 0 ? (
        <p className="empty-note">This server did not report any tools.</p>
    ) : (
        <CapabilityList items={items} onTry={tryTool}/>
    );

    const details: [string, string][] = [
        ['Endpoint', tool.endpoint],
        ['Transport', tool.transport === 'STREAMABLE_HTTP' ? 'Streamable HTTP' : tool.transport],
        ['Authentication', `${auth}${tool.authType === 'BEARER' ? ' (stored encrypted, never sent to the AI)' : ''}`],
    ];

    return (
        <div className="tools-page tool-detail">
            <nav className="crumbs" aria-label="Breadcrumb">
                <button type="button" className="link-btn" onClick={onBack}><ArrowLeft size={15}/>Tools</button>
                <span aria-hidden="true">/</span>
                <span aria-current="page">{tool.name}</span>
            </nav>

            <header className="detail-head">
                <ToolLogo name={tool.name} item={item} size={64}/>
                <div className="detail-title">
                    <h1>{tool.name}</h1>
                    <p>
                        <span className={`status-dot ${tool.status}`}/>
                        {connected ? 'Connected' : STATUS_LABEL[tool.status]}
                        {connected && <> &middot; Last sync {relativeTime(tool.lastConnectedAt)}</>}
                        {host && <span className="host-chip">{host}</span>}
                    </p>
                </div>
                <div className="detail-actions">
                    {connected && items && items.length > 0 && (
                        <Button onClick={() => panelRef.current?.scrollIntoView?.({behavior: 'smooth', block: 'start'})}>Try a tool</Button>
                    )}
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
                <div><span>Status</span><strong>{connected ? 'Connected' : STATUS_LABEL[tool.status]}</strong></div>
                <div><span>Last sync</span><strong>{relativeTime(tool.lastConnectedAt)}</strong></div>
                <div><span>Tools exposed</span><strong>{items ? items.length : '-'}</strong></div>
                <div><span>Response time</span><strong>{ms !== null ? `${ms} ms` : '-'}</strong></div>
            </div>

            <div className="detail-grid">
                <Tabs
                    className="detail-tabs"
                    items={[
                        {key: 'capabilities', label: 'Capabilities', children: capabilitiesTab},
                        {
                            key: 'settings', label: 'Settings', children: (
                                <>
                                    <dl className="detail-list">
                                        {details.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}
                                    </dl>
                                    <p className="hint-note">Editing a connection is not available yet. To change the address or token, add the tool again.</p>
                                </>
                            ),
                        },
                    ]}
                />
                <div ref={panelRef} className="side-col">
                    {connected && items && items.length > 0 ? (
                        <ToolTestPanel toolId={tool.id} items={items} selected={selected} onSelect={setSelected}/>
                    ) : (
                        <section className="side-card"><h3>Try a tool</h3><p className="mut small">Available once this tool is connected.</p></section>
                    )}
                </div>
            </div>
        </div>
    );
};

export default ToolDetailPage;
