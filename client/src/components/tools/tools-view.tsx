import {useCallback, useEffect, useState} from 'react';
import {Alert, Button, Dropdown, message} from 'antd';
import {Ellipsis, Plus} from 'lucide-react';
import './style/tools.scss';
import ConnectToolModal from './connect-tool-modal';
import ToolDetailView from './tool-detail-view';
import ToolLogo from './tool-logo';
import {CATALOG, CATEGORY_FILTERS, CUSTOM_ITEM} from './tool-catalog';
import type {CatalogItem, ToolCategory} from './tool-catalog';
import {toolsApi} from './tools-api';
import type {McpServerDto} from './tools-api';
import {findCatalogItem, relativeTime, STATUS_LABEL} from './tools-utils';

const ToolsView = () => {
    const [tools, setTools] = useState<McpServerDto[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [capCounts, setCapCounts] = useState<Record<string, number>>({});
    const [busyId, setBusyId] = useState<string | null>(null);
    const [filter, setFilter] = useState<'All' | ToolCategory>('All');
    const [modal, setModal] = useState<{ open: boolean; preset: CatalogItem | null; session: number }>({open: false, preset: null, session: 0});
    const [selectedId, setSelectedId] = useState<string | null>(null);

    const refresh = useCallback(async () => {
        try {
            const list = await toolsApi.list();
            setTools(list);
            setLoadError(null);
            const connected = list.filter((t) => t.status === 'CONNECTED');
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

    const runAction = async (tool: McpServerDto, action: 'connect' | 'disconnect') => {
        setBusyId(tool.id);
        try {
            if (action === 'connect') {
                await toolsApi.connect(tool.id);
                if ((await toolsApi.status(tool.id)) === 'CONNECTED') message.success(`${tool.name} connected.`);
                else message.error(`${tool.name} could not connect. Check its address and token.`);
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

    const openWizard = (preset: CatalogItem | null) => setModal((m) => ({open: true, preset, session: m.session + 1}));

    const selected = selectedId ? tools.find((t) => t.id === selectedId) : undefined;
    if (selected) {
        return (
            <ToolDetailView
                tool={selected}
                busy={busyId === selected.id}
                onBack={() => setSelectedId(null)}
                onConnect={() => void runAction(selected, 'connect')}
                onDisconnect={() => void runAction(selected, 'disconnect')}
            />
        );
    }

    const catalog = CATALOG.filter((c) => filter === 'All' || c.category === filter);
    const showCustom = filter === 'All' || filter === 'Custom';

    return (
        <div className="tools-page">
            <div className="tools-head">
                <div>
                    <h1>Tools</h1>
                    <p className="page-sub">Connect MCP servers to give Connect AI access to your engineering context.</p>
                </div>
                <button type="button" className="add-tool-btn" onClick={() => openWizard(null)}>
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
                        const item = findCatalogItem(tool.name);
                        const connected = tool.status === 'CONNECTED';
                        return (
                            <article key={tool.id} className="conn-card">
                                <div className="conn-top">
                                    <ToolLogo name={tool.name} item={item}/>
                                    <div className="conn-id">
                                        <button type="button" className="link-btn name" onClick={() => setSelectedId(tool.id)}>{tool.name}</button>
                                        <span className="conn-status">
                                            <span className={`status-dot ${tool.status}`}/>
                                            {STATUS_LABEL[tool.status]}{connected && <> &middot; Last sync {relativeTime(tool.lastConnectedAt)}</>}
                                        </span>
                                    </div>
                                    <Dropdown
                                        trigger={['click']}
                                        menu={{
                                            items: [
                                                {key: 'view', label: 'View details'},
                                                connected ? {key: 'disconnect', label: 'Disconnect', danger: true} : {key: 'connect', label: 'Connect'},
                                            ],
                                            onClick: ({key}) => {
                                                if (key === 'view') setSelectedId(tool.id);
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
                                    {!connected && (
                                        <Button type="primary" size="small" loading={busyId === tool.id} onClick={() => void runAction(tool, 'connect')}>
                                            {tool.status === 'REGISTERED' ? 'Connect' : 'Reconnect'}
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
                    const added = tools.some((t) => findCatalogItem(t.name)?.id === item.id);
                    return (
                        <article key={item.id} className="catalog-card">
                            <div className="catalog-top"><ToolLogo name={item.name} item={item} size={36}/><h3>{item.name}</h3></div>
                            <p>{item.blurb}</p>
                            <div className="catalog-foot">
                                <span className="pill">{item.category}</span>
                                <button type="button" className="link-btn" onClick={() => openWizard(item)}>{added ? 'Add another' : 'Connect'}</button>
                            </div>
                        </article>
                    );
                })}
                {showCustom && (
                    <button type="button" className="catalog-card custom" onClick={() => openWizard(null)}>
                        <Plus size={26}/>
                        <strong>Add Custom MCP Server</strong>
                        <span>{CUSTOM_ITEM.blurb}</span>
                    </button>
                )}
                {catalog.length === 0 && !showCustom && <p className="empty-note">No tools in this category yet.</p>}
            </div>

            <ConnectToolModal
                open={modal.open}
                preset={modal.preset}
                session={modal.session}
                onClose={() => setModal((m) => ({...m, open: false}))}
                onChanged={() => void refresh()}
            />
        </div>
    );
};

export default ToolsView;
