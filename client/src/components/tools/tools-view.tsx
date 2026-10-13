import {useCallback, useEffect, useState} from 'react';
import {Alert, Button, Dropdown, Modal, message} from 'antd';
import {Ellipsis, Plus} from 'lucide-react';
import './style/tools.scss';
import ConnectToolModal from './connect-tool-modal';
import ToolDetailView from './tool-detail-view';
import ToolLogo from './tool-logo';
import {CATEGORY_FILTERS, mapCatalogEntry} from './tool-catalog';
import type {CatalogItem, ToolCategory} from './tool-catalog';
import {toolsApi} from './tools-api';
import {openSignInPopup, signInWithPopup} from './oauth';
import type {McpServerDto} from './tools-api';
import {findCatalogItem, relativeTime, STATUS_LABEL} from './tools-utils';

const ToolsView = () => {
    const [tools, setTools] = useState<McpServerDto[]>([]);
    const [catalogItems, setCatalogItems] = useState<CatalogItem[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [capCounts, setCapCounts] = useState<Record<string, number>>({});
    const [busyId, setBusyId] = useState<string | null>(null);
    const [filter, setFilter] = useState<'All' | ToolCategory>('All');
    const [modal, setModal] = useState<{ open: boolean; preset: CatalogItem | null; session: number }>({open: false, preset: null, session: 0});
    const [selectedId, setSelectedId] = useState<string | null>(null);
    const [confirm, confirmHolder] = Modal.useModal();

    const refresh = useCallback(async () => {
        try {
            const [list, catalogResponse] = await Promise.all([toolsApi.list(), toolsApi.catalog()]);
            setTools(list);
            const catalog = catalogResponse.map(mapCatalogEntry);
            setCatalogItems(catalog);
            setLoadError(null);
            const connected = list.filter((t) => t.runtimeConnected);
            const results = await Promise.allSettled(connected.map((t) => toolsApi.capabilities(t.id)));
            const counts: Record<string, number> = {};
            results.forEach((r, i) => {
                if (r.status === 'fulfilled') counts[connected[i].id] = r.value.length;
            });
            setCapCounts(counts);
        } catch (error) {
            setLoadError(error instanceof Error ? error.message : 'Could not load your tools.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        void refresh();
    }, [refresh]);

    /** OAuth callback exchanges the token, reconnects MCP, and discovers tools before it succeeds. */
    const signIn = (tool: McpServerDto) => {
        const popup = openSignInPopup(); // synchronously inside the click, or the browser blocks it
        void (async () => {
            setBusyId(tool.id);
            try {
                const result = await signInWithPopup(tool.id, popup);
                if (!result.ok) {
                    message.error(result.message);
                    return;
                }
                message.success(`${tool.name} signed in, connected, and tools were discovered.`);
            } catch (error) {
                message.error(error instanceof Error ? error.message : 'Sign-in failed.');
            } finally {
                setBusyId(null);
                await refresh();
            }
        })();
    };

    const needsSignIn = (tool: McpServerDto) => tool.authType === 'OAUTH'
        && (!tool.secretConfigured || tool.status === 'REAUTH_REQUIRED');

    const runAction = async (tool: McpServerDto, action: 'connect' | 'disconnect') => {
        setBusyId(tool.id);
        try {
            if (action === 'connect') {
                // Success is only reported when the backend confirms a completed handshake.
                const result = await toolsApi.connect(tool.id);
                if (result?.initialized) message.success(`${tool.name} connected.`);
                else message.error(`${tool.name} could not connect${result?.error ? `: ${result.error}` : '.'}`);
            } else {
                await toolsApi.disconnect(tool.id);
                message.success(`${tool.name} disconnected.`);
            }
        } catch (error) {
            message.error(error instanceof Error ? error.message : 'Something went wrong.');
        } finally {
            setBusyId(null);
            await refresh();
        }
    };

    /**
     * Remove = disconnect (when there is a live session) and then delete the saved connection.
     * The backend refuses to delete a connected server, so the order is enforced on both sides.
     */
    const removeTool = (tool: McpServerDto) => {
        void confirm.confirm({
            title: `Remove ${tool.name}?`,
            content: (tool.runtimeConnected ? 'It will be disconnected first. ' : '')
                + 'The saved connection and its token are deleted. Past investigations stay in history.',
            okText: 'Remove',
            okButtonProps: {danger: true},
            onOk: async () => {
                setBusyId(tool.id);
                try {
                    if (tool.runtimeConnected) await toolsApi.disconnect(tool.id);
                    await toolsApi.remove(tool.id);
                    message.success(`${tool.name} removed.`);
                    if (selectedId === tool.id) setSelectedId(null);
                } catch (error) {
                    message.error(error instanceof Error ? error.message : 'The connection could not be removed.');
                } finally {
                    setBusyId(null);
                    await refresh();
                }
            },
        });
    };

    const openWizard = (preset: CatalogItem) => setModal((m) => ({open: true, preset, session: m.session + 1}));

    const selected = selectedId ? tools.find((t) => t.id === selectedId) : undefined;
    if (selected) {
        return (
            <>
                {confirmHolder}
                <ToolDetailView
                tool={selected}
                catalogItem={catalogItems.find((item) => item.key === selected.catalogKey)}
                busy={busyId === selected.id}
                onBack={() => setSelectedId(null)}
                onConnect={() => void runAction(selected, 'connect')}
                onDisconnect={() => void runAction(selected, 'disconnect')}
                onRemove={() => removeTool(selected)}
                onChanged={() => void refresh()}
                onSignIn={selected.authType === 'OAUTH' ? () => signIn(selected) : undefined}
                />
            </>
        );
    }

    const catalog = catalogItems
        .filter((c) => filter === 'All' || c.category === filter)
        .sort((a, b) => Number(b.status === 'CONNECTED') - Number(a.status === 'CONNECTED'));
    const showCustom = filter === 'All' || filter === 'Custom';
    const customItem = catalogItems.find((item) => item.key === 'custom');

    return (
        <div className="tools-page">
            <div className="tools-head">
                <div>
                    <h1>Tools</h1>
                    <p className="page-sub">Connect MCP servers to give Connect AI access to your engineering context.</p>
                </div>
                <button type="button" className="add-tool-btn" disabled={!customItem} onClick={() => customItem && openWizard(customItem)}>
                    <Plus size={17}/>Add a Tool
                </button>
            </div>

            <h2 className="section-title">Your connections ({tools.length})</h2>
            {loadError && (
                <Alert type="error" showIcon title={loadError} action={<Button size="small" onClick={() => void refresh()}>Try again</Button>}/>
            )}
            {loading ? (
                <p className="empty-note">Loading your connections</p>
            ) : tools.length === 0 && !loadError ? (
                <p className="empty-note">Nothing is connected yet. Pick a tool below to get started.</p>
            ) : (
                <div className="conn-grid">
                    {tools.map((tool) => {
                        const item = findCatalogItem(tool.name, catalogItems);
                        const connected = tool.runtimeConnected && tool.status === 'CONNECTED';
                        return (
                            <article key={tool.id} className="conn-card">
                                <div className="conn-top">
                                    <ToolLogo name={tool.name} item={item}/>
                                    <div className="conn-id">
                                        <button type="button" className="link-btn name" onClick={() => setSelectedId(tool.id)}>{tool.name}</button>
                                        <span className="conn-status">
                                            <span className={`status-dot ${tool.status}`}/>
                                            {connected ? 'Connected' : STATUS_LABEL[tool.status === 'CONNECTED' ? 'DISCONNECTED' : tool.status]}{connected && <> &middot; Last sync {relativeTime(tool.lastConnectedAt)}</>}
                                        </span>
                                    </div>
                                    <Dropdown
                                        trigger={['click']}
                                        menu={{
                                            items: [
                                                {key: 'view', label: 'View details'},
                                                connected ? {key: 'disconnect', label: 'Disconnect'} : {key: 'connect', label: 'Connect'},
                                                {type: 'divider'},
                                                {key: 'remove', label: 'Remove', danger: true},
                                            ],
                                            onClick: ({key}) => {
                                                if (key === 'view') setSelectedId(tool.id);
                                                else if (key === 'remove') removeTool(tool);
                                                else void runAction(tool, key === 'connect' ? 'connect' : 'disconnect');
                                            },
                                        }}
                                    >
                                        <button type="button" className="icon-btn" aria-label={`Actions for ${tool.name}`}><Ellipsis size={18}/></button>
                                    </Dropdown>
                                </div>
                                <div className="conn-bottom">
                                    {connected && capCounts[tool.id] !== undefined && <span className="pill">{capCounts[tool.id]} capabilities</span>}
                                    {tool.status === 'FAILED' && <span className="pill bad">Connection failed</span>}
                                    {tool.status === 'REAUTH_REQUIRED' && <span className="pill bad">Re-authorize to continue</span>}
                                    {!connected && tool.lastError && <span className="conn-error" title={tool.lastError}>{tool.lastError}</span>}
                                    {!connected && (
                                        <Button type="primary" size="small" loading={busyId === tool.id}
                                                onClick={() => (needsSignIn(tool) ? signIn(tool) : void runAction(tool, 'connect'))}>
                                            {needsSignIn(tool) ? 'Sign in' : tool.status === 'REGISTERED' ? 'Connect' : 'Reconnect'}
                                        </Button>
                                    )}
                                </div>
                            </article>
                        );
                    })}
                </div>
            )}

            <h2 className="section-title">Available to connect</h2>
            <div className="chips" role="group" aria-label="Filter by category">
                {(['All', ...CATEGORY_FILTERS] as const).map((c) => (
                    <button key={c} type="button" className="chip" aria-pressed={filter === c} onClick={() => setFilter(c)}>{c}</button>
                ))}
            </div>
            <div className="catalog-grid">
                {catalog.map((item) => {
                    const added = item.connectionCount > 0 || tools.some((t) => t.catalogKey === item.key);
                    const availableMethod = item.methods.find((candidate) => candidate.supported);
                    const cardStatus = item.adminSetupRequired ? 'Administrator setup required'
                        : item.status === 'AVAILABLE' ? `Ready to connect${availableMethod ? ` using ${availableMethod.label}` : ''}`
                            : item.status === 'NEEDS_SIGN_IN' ? 'Sign-in required'
                                : item.status === 'CONNECTED' ? 'Connected'
                                    : item.status === 'CONNECTION_ERROR' ? 'Connection failed' : 'Setup required';
                    return (
                        <article key={item.id} className="catalog-card">
                            <div className="catalog-top"><ToolLogo name={item.name} item={item} size={36}/><h3>{item.name}</h3></div>
                            <p>{item.description}</p>
                            <p className={`catalog-state${item.adminSetupRequired ? ' admin-setup' : ''}`} title={item.statusReason}>{cardStatus}</p>
                            <div className="catalog-foot">
                                <span className="pill">{item.category}</span>
                                <button type="button" className="link-btn" disabled={!item.methods.some((candidate) => candidate.supported)}
                                        onClick={() => openWizard(item)}>{added ? 'Add another connection' : 'Connect'}</button>
                            </div>
                        </article>
                    );
                })}
                {showCustom && (
                    <button type="button" className="catalog-card custom" disabled={!customItem} onClick={() => customItem && openWizard(customItem)}>
                        <Plus size={26}/>
                        <strong>Add Custom MCP Server</strong>
                        <span>{customItem?.description ?? 'Custom MCP server setup is unavailable.'}</span>
                    </button>
                )}
                {catalog.length === 0 && !showCustom && <p className="empty-note">No tools in this category yet.</p>}
            </div>

            {confirmHolder}
            {modal.preset && <ConnectToolModal
                open={modal.open}
                preset={modal.preset}
                session={modal.session}
                existingNames={tools.map((t) => t.name)}
                onClose={() => setModal((m) => ({...m, open: false}))}
                onChanged={() => void refresh()}
            />}
        </div>
    );
};

export default ToolsView;
