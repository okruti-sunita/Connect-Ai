import { useEffect, useState } from "react";
import { Link2, Plus, RefreshCw, Unplug } from "lucide-react";
import GithubMark from "../shared/GithubMark";
import { message, Spin } from "antd";

import "./style/connection.scss";
import ToolConnectModal from "./connect-tools-modal";
import {
    API_BASE_URL,
    CATEGORIES,
    TOOL_CATALOG,
    type CatalogTool
} from "./tool-catalog";

type ToolStatus =
    | "REGISTERED"
    | "CONNECTING"
    | "CONNECTED"
    | "FAILED"
    | "DISCONNECTED";

interface RegisteredTool {
    id: string | number;
    name: string;
    endpoint: string;
    status: ToolStatus;
    authType?: "NONE" | "BEARER";
}

interface ConnectionStatusResponse {
    status: ToolStatus;
    toolCount?: number;
    tools?: string[];
    lastError?: string | null;
    connectedAt?: string | null;
}

const STATUS_LABEL: Record<ToolStatus, string> = {
    REGISTERED: "Registered",
    CONNECTING: "Connecting",
    CONNECTED: "Connected",
    FAILED: "Failed",
    DISCONNECTED: "Disconnected"
};

const ConnectionView = () => {
    const [open, setOpen] = useState(false);
    const [preset, setPreset] = useState<CatalogTool | null>(null);
    const [category, setCategory] = useState<string>("All");

    const [registeredTools, setRegisteredTools] = useState<RegisteredTool[]>([]);
    const [loading, setLoading] = useState(true);
    const [connectingId, setConnectingId] = useState<string | number | null>(null);
    const [disconnectingId, setDisconnectingId] = useState<string | number | null>(null);

    /* ---------- modal ---------- */
    const openModal = (tool: CatalogTool | null) => {
        setPreset(tool);
        setOpen(true);
    };

    /* ---------- helpers ---------- */
    const updateStatus = (id: string | number, status: ToolStatus) =>
        setRegisteredTools((tools) =>
            tools.map((t) => (String(t.id) === String(id) ? { ...t, status } : t))
        );

    /* ---------- GET /api/tools ---------- */
    const fetchRegisteredTools = async () => {
        try {
            setLoading(true);
            const response = await fetch(API_BASE_URL);
            if (!response.ok) throw new Error(`API failed with status: ${response.status}`);
            const data: RegisteredTool[] = await response.json();
            setRegisteredTools(data);
        } catch (error) {
            console.error("Error while fetching registered tools:", error);
            message.error("Unable to load registered tools.");
        } finally {
            setLoading(false);
        }
    };

    /* ---------- GET /api/tools/{id}/connection ---------- */
    const getConnectionStatus = async (id: string | number) => {
        try {
            const response = await fetch(`${API_BASE_URL}/${id}/connection`);
            if (!response.ok) throw new Error(`API failed with status: ${response.status}`);
            const data: ConnectionStatusResponse = await response.json();
            updateStatus(id, data.status);
        } catch (error) {
            console.error("Error while fetching connection status:", error);
        }
    };

    /* ---------- POST /api/tools/{id}/connect ---------- */
    const handleConnectButton = async (id: string | number) => {
        try {
            setConnectingId(id);
            updateStatus(id, "CONNECTING");

            const response = await fetch(`${API_BASE_URL}/${id}/connect`, {
                method: "POST",
                headers: { "Content-Type": "application/json" }
            });
            if (!response.ok) throw new Error(`API failed with status: ${response.status}`);

            message.success("Connected");
            await getConnectionStatus(id);
        } catch (error) {
            console.error("Error while connecting:", error);
            updateStatus(id, "FAILED");
            message.error("Failed to connect. Check the endpoint and token.");
        } finally {
            setConnectingId(null);
        }
    };

    /* ---------- POST /api/tools/{id}/disconnect ---------- */
    const handleDisconnectButton = async (id: string | number) => {
        try {
            setDisconnectingId(id);

            const response = await fetch(`${API_BASE_URL}/${id}/disconnect`, {
                method: "POST",
                headers: { "Content-Type": "application/json" }
            });
            if (!response.ok) throw new Error(`API failed with status: ${response.status}`);

            message.success("Disconnected");
            await getConnectionStatus(id);
        } catch (error) {
            console.error("Error while disconnecting:", error);
            message.error("Failed to disconnect.");
        } finally {
            setDisconnectingId(null);
        }
    };

    useEffect(() => {
        fetchRegisteredTools();
    }, []);

    const isGithubTool = (tool: RegisteredTool) =>
        tool.name.toLowerCase().includes("github");

    /* Hide catalog entries that are already registered */
    const catalogItems = TOOL_CATALOG.filter(
        (t) =>
            (category === "All" || t.category === category) &&
            !registeredTools.some((r) => r.name.toLowerCase().includes(t.key))
    );

    return (
        <div className="connections-container">
            {/* ===== Header ===== */}
            <div className="connections-header">
                <div className="header">
                    <h1 className="title">Tools</h1>
                    <p className="sub-title">
                        Connect MCP servers to give Connect AI access to your engineering
                        context.
                    </p>
                </div>

                <div className="connections-header-actions">
                    <button
                        type="button"
                        className="refresh-tools-button"
                        onClick={fetchRegisteredTools}
                        disabled={loading}
                    >
                        <RefreshCw
                            size={16}
                            className={loading ? "refresh-icon spinning" : "refresh-icon"}
                        />
                        <span>Refresh</span>
                    </button>

                    <button
                        type="button"
                        className="connect-tools-button"
                        onClick={() => openModal(null)}
                    >
                        <Plus size={17} />
                        <span>Add a tool</span>
                    </button>
                </div>
            </div>

            {/* ===== Your connections ===== */}
            <h2 className="section-title">Your connections ({registeredTools.length})</h2>

            <div className="registered-tools-container">
                {loading && (
                    <div className="tools-loading-state">
                        <Spin size="large" />
                        <p>Loading your tools...</p>
                    </div>
                )}

                {!loading && registeredTools.length === 0 && (
                    <div className="tools-empty-state">
                        <div className="empty-icon">
                            <Link2 size={28} />
                        </div>
                        <h3>No tools connected yet</h3>
                        <p>Pick a tool below to give Connect AI context from your engineering systems.</p>
                    </div>
                )}

                {!loading &&
                    registeredTools.map((tool) => {
                        const isConnecting = String(connectingId) === String(tool.id);
                        const isDisconnecting = String(disconnectingId) === String(tool.id);
                        const isConnected = tool.status === "CONNECTED";
                        const isDisconnected =
                            tool.status === "REGISTERED" || tool.status === "DISCONNECTED";
                        const busy = isConnecting || isDisconnecting;

                        return (
                            <div key={tool.id} className="card">
                                <div className="card-header">
                                    <div className="tool-info">
                                        <div className="tool-icon">
                                            {isGithubTool(tool) ? (
                                                <GithubMark size={24} />
                                            ) : (
                                                <Link2 size={24} />
                                            )}
                                        </div>
                                        <div>
                                            <h3>{tool.name}</h3>
                                            <p className={`tool-status-line ${tool.status}`}>
                                                <span className="status-dot" />
                                                {STATUS_LABEL[tool.status]}
                                            </p>
                                        </div>
                                    </div>
                                </div>

                                <p className="tool-endpoint">{tool.endpoint}</p>

                                <div className="card-footer">
                                    <div className="tool-meta">
                                        <span>MCP server</span>
                                        {tool.authType && <span>Auth: {tool.authType}</span>}
                                    </div>

                                    <div className="card-button-container">
                                        <button
                                            type="button"
                                            className="card-button connect"
                                            disabled={isConnected || busy}
                                            onClick={() => handleConnectButton(tool.id)}
                                        >
                                            {isConnecting ? (
                                                <>
                                                    <Spin size="small" />
                                                    <span>Connecting...</span>
                                                </>
                                            ) : (
                                                <>
                                                    <Link2 size={16} />
                                                    <span>Connect</span>
                                                </>
                                            )}
                                        </button>

                                        <button
                                            type="button"
                                            className="card-button disconnect"
                                            disabled={isDisconnected || busy}
                                            onClick={() => handleDisconnectButton(tool.id)}
                                        >
                                            {isDisconnecting ? (
                                                <>
                                                    <Spin size="small" />
                                                    <span>Disconnecting...</span>
                                                </>
                                            ) : (
                                                <>
                                                    <Unplug size={16} />
                                                    <span>Disconnect</span>
                                                </>
                                            )}
                                        </button>
                                    </div>
                                </div>
                            </div>
                        );
                    })}
            </div>

            {/* ===== Available to connect ===== */}
            <h2 className="section-title">Available to connect</h2>

            <div className="category-chips">
                {CATEGORIES.map((c) => (
                    <button
                        key={c}
                        type="button"
                        className={`chip ${category === c ? "active" : ""}`}
                        onClick={() => setCategory(c)}
                    >
                        {c}
                    </button>
                ))}
            </div>

            <div className="catalog-grid">
                {category !== "Custom" &&
                    catalogItems.map((t) => (
                        <div key={t.key} className="catalog-card">
                            <div className="catalog-top">
                                <div className="catalog-icon">{t.icon}</div>
                                <h4>{t.name}</h4>
                            </div>
                            <p>{t.description}</p>
                            <div className="catalog-bottom">
                                <span className="tag">{t.category}</span>
                                <button
                                    type="button"
                                    className="link-button"
                                    onClick={() => openModal(t)}
                                >
                                    Connect
                                </button>
                            </div>
                        </div>
                    ))}

                {(category === "All" || category === "Custom") && (
                    <button
                        type="button"
                        className="catalog-card custom"
                        onClick={() => openModal(null)}
                    >
                        <Plus size={26} />
                        <h4>Add custom MCP server</h4>
                        <p>Manually configure a connection.</p>
                    </button>
                )}
            </div>

            <ToolConnectModal
                open={open}
                preset={preset}
                onClose={() => setOpen(false)}
                onToolRegistered={fetchRegisteredTools}
            />
        </div>
    );
};

export default ConnectionView;
