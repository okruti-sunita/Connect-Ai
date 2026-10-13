import {useEffect, useState} from 'react';
import {Button, Input, Modal, Switch, Tabs, message} from 'antd';
import {ArrowLeft, Search} from 'lucide-react';
import ToolLogo from './tool-logo';
import {toolsApi, type Capability, type McpServerDto} from './tools-api';
import {relativeTime, STATUS_LABEL} from './tools-utils';
import type {CatalogItem} from './tool-catalog';

interface ToolDetailViewProps {
    tool: McpServerDto;
    catalogItem?: CatalogItem;
    busy: boolean;
    onBack: () => void;
    onConnect: () => void;
    onDisconnect: () => void;
    /** Disconnects (if needed) and deletes this connection, after confirmation. */
    onRemove: () => void;
    onChanged: () => void;
    /** Only for OAuth connections: sign in again (new token) and reconnect. */
    onSignIn?: () => void;
}

const ToolDetailView = ({tool, catalogItem, busy, onBack, onConnect, onDisconnect, onRemove, onChanged, onSignIn}: ToolDetailViewProps) => {
    const item = catalogItem;
    const [capabilities, setCapabilities] = useState<Capability[] | null>(null);
    const [failed, setFailed] = useState(false);
    const [query, setQuery] = useState('');
    const [reloadKey, setReloadKey] = useState(0);
    const [replaceOpen, setReplaceOpen] = useState(false);
    const [replacement, setReplacement] = useState('');
    const [replacing, setReplacing] = useState(false);
    const connected = tool.runtimeConnected && tool.status === 'CONNECTED';

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

    const replaceCredential = async () => {
        if (!replacement.trim()) return;
        setReplacing(true);
        try {
            if (tool.runtimeConnected) await toolsApi.disconnect(tool.id);
            await toolsApi.replaceBearerCredential(tool.id, replacement.trim());
            setReplacement('');
            setReplaceOpen(false);
            const result = await toolsApi.connect(tool.id);
            if (result?.initialized) message.success(`${tool.name} credential replaced and MCP connection verified.`);
            else message.error(`Credential saved, but ${tool.name} MCP connection failed${result?.error ? `: ${result.error}` : '.'}`);
        } catch (error) {
            message.error(error instanceof Error ? error.message : 'Could not replace the credential.');
        } finally {
            setReplacing(false);
            onChanged();
        }
    };

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
        ['Authentication', tool.authType === 'OAUTH'
            ? (tool.secretConfigured ? `OAuth (${tool.oauthProvider ?? 'provider'}, token stored encrypted)` : 'OAuth, not signed in')
            : tool.authType === 'BEARER' ? 'Bearer token (stored encrypted)' : 'None'],
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
                        <Button loading={busy} onClick={onDisconnect}>Disconnect</Button>
                    ) : (
                        <Button type="primary" loading={busy} disabled={tool.status === 'REAUTH_REQUIRED'} onClick={onConnect}>
                            {tool.status === 'REAUTH_REQUIRED' ? 'Re-authorize first' : tool.status === 'REGISTERED' ? 'Connect' : 'Reconnect'}
                        </Button>
                    )}
                    {onSignIn && (
                        <Button disabled={busy} onClick={onSignIn}>
                            {tool.secretConfigured ? 'Re-authorize' : 'Sign in'}
                        </Button>
                    )}
                    {tool.authType === 'BEARER' && (
                        <Button disabled={busy} onClick={() => setReplaceOpen(true)}>Replace token</Button>
                    )}
                    <Button danger disabled={busy} onClick={onRemove}>Remove</Button>
                </div>
            </header>
            {!connected && tool.lastError && (
                <p className="inline-alert" role="alert">{tool.lastError}</p>
            )}
            <div className="info-tiles">
                <div><span>Status</span><strong>{STATUS_LABEL[tool.status]}</strong></div>
                <div><span>Last connected</span><strong>{relativeTime(tool.lastConnectedAt)}</strong></div>
                <div><span>Capabilities exposed</span><strong>{capabilities ? capabilities.length : '-'}</strong></div>
                <div><span>Authentication</span><strong>{tool.authType === 'OAUTH'
                    ? (tool.secretConfigured ? `Signed in (${tool.oauthProvider ?? 'OAuth'})` : 'OAuth: not signed in')
                    : tool.authType === 'BEARER' ? 'Access token' : 'None'}</strong></div>
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
            <Modal open={replaceOpen} title="Replace bearer token" okText="Save and reconnect"
                   confirmLoading={replacing} onOk={() => void replaceCredential()}
                   onCancel={() => { if (!replacing) { setReplaceOpen(false); setReplacement(''); } }}>
                <p>The previous token cannot be recovered if its encryption key changed. Enter a new token with the required read-only access. It is encrypted by the backend and is never returned to this page.</p>
                <Input.Password autoComplete="new-password" value={replacement}
                                onChange={(event) => setReplacement(event.target.value)}
                                placeholder="Paste a new access token" aria-label="New bearer token"/>
            </Modal>
        </div>
    );
};

export default ToolDetailView;
