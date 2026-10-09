import { useEffect, useState } from "react";
import { Form, Input, Modal, Segmented, Select, message } from "antd";
import { Plug, ShieldCheck, Check, CircleAlert, Lock } from "lucide-react";
import "./style/connection.scss";
import {
    API_BASE_URL,
    GITHUB_CLOUD_URL,
    type CatalogTool
} from "./tool-catalog";

interface ToolConnectModalProps {
    open: boolean;
    preset: CatalogTool | null; // null = custom MCP server
    onClose: () => void;
    onToolRegistered: () => void;
}

interface FormValues {
    name: string;
    deployment: "CLOUD" | "ENTERPRISE" | "CUSTOM";
    endpoint: string;
    authType: "NONE" | "BEARER";
    secret?: string;
}

interface TestResult {
    ok: boolean;
    toolCount?: number;
    tools?: string[];
    error?: string;
}

const ToolConnectModal = ({
    open,
    preset,
    onClose,
    onToolRegistered
}: ToolConnectModalProps) => {
    const [form] = Form.useForm<FormValues>();
    const [step, setStep] = useState<0 | 1>(0);
    const [testing, setTesting] = useState(false);
    const [result, setResult] = useState<TestResult | null>(null);
    const [createdId, setCreatedId] = useState<string | number | null>(null);

    const authType = Form.useWatch("authType", form);
    const isGithub = preset?.key === "github";

    /* Reset every time the modal opens */
    useEffect(() => {
        if (!open) return;
        form.resetFields();
        form.setFieldsValue({
            name: preset ? `${preset.name} - Main` : "",
            deployment: isGithub ? "CLOUD" : "CUSTOM",
            endpoint: preset?.endpoint ?? "",
            authType: "BEARER"
        });
        setStep(0);
        setResult(null);
        setCreatedId(null);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, preset]);

    const handleDeploymentChange = (value: string | number) => {
        form.setFieldValue("endpoint", value === "CLOUD" ? GITHUB_CLOUD_URL : "");
    };

    /* Register (once) -> connect -> read status */
    const handleTest = async () => {
        try {
            const values = await form.validateFields();
            setTesting(true);

            let id = createdId;

            if (id === null) {
                const res = await fetch(API_BASE_URL, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        name: values.name,
                        transport: "STREAMABLE_HTTP",
                        endpoint: values.endpoint,
                        authType: values.authType,
                        secret:
                            values.authType === "BEARER" ? values.secret : null,
                        enabled: true
                    })
                });
                if (!res.ok) throw new Error(`Registration failed (${res.status})`);

                id = (await res.json()).id;
                setCreatedId(id);
                onToolRegistered();
            }

            const conn = await fetch(`${API_BASE_URL}/${id}/connect`, {
                method: "POST"
            });
            if (!conn.ok) throw new Error(`Connection failed (${conn.status})`);

            const statusRes = await fetch(`${API_BASE_URL}/${id}/connection`);
            const status = await statusRes.json();

            setResult({
                ok: status.status === "CONNECTED",
                toolCount: status.toolCount,
                tools: status.tools,
                error: status.lastError ?? undefined
            });
            setStep(1);
            onToolRegistered();
        } catch (e) {
            const err = e as { errorFields?: unknown; message?: string };
            if (err.errorFields) return; // form validation, antd shows it inline
            console.error("Test connection failed:", e);
            setResult({ ok: false, error: err.message ?? "Unknown error" });
            setStep(1);
        } finally {
            setTesting(false);
        }
    };

    const handleFinish = () => {
        message.success("Tool saved");
        onToolRegistered();
        onClose();
    };

    const footer =
        step === 0 ? (
            <div className="modal-footer-row">
                <button type="button" className="btn-secondary" onClick={onClose}>
                    Cancel
                </button>
                <button
                    type="button"
                    className="btn-primary"
                    onClick={handleTest}
                    disabled={testing}
                >
                    <Plug size={16} />
                    {testing ? "Testing..." : "Test Connection"}
                </button>
            </div>
        ) : (
            <div className="modal-footer-row">
                <button
                    type="button"
                    className="btn-secondary"
                    onClick={() => setStep(0)}
                >
                    Back
                </button>
                <button
                    type="button"
                    className="btn-primary"
                    onClick={handleFinish}
                    disabled={!result?.ok}
                >
                    Save & Finish
                </button>
            </div>
        );

    return (
        <Modal
            open={open}
            onCancel={onClose}
            footer={footer}
            centered
            width={820}
            forceRender
            title={null}
            className="tool-register-modal"
        >
            {/* Header */}
            <div className="modal-hero">
                <div className="modal-hero-icon">
                    {preset?.icon ?? <Plug size={24} />}
                </div>
                <div>
                    <h2>Connect {preset?.name ?? "custom MCP server"}</h2>
                    <p>{preset?.subtitle ?? "Manually configure a connection"}</p>
                </div>
            </div>

            {/* Stepper */}
            <div className="modal-stepper">
                <div className={`step ${step === 0 ? "active" : "done"}`}>
                    <span className="step-dot">
                        {step === 1 ? <Check size={12} /> : 1}
                    </span>
                    Configure
                </div>
                <span className="step-line" />
                <div className={`step ${step === 1 ? "active" : ""}`}>
                    <span className="step-dot">2</span>
                    Test & Save
                </div>
            </div>

            <div className="modal-body-grid">
                <div className="modal-main">
                    {/* Form stays mounted so values survive "Back" */}
                    <div style={{ display: step === 0 ? "block" : "none" }}>
                        <h3>Configuration</h3>
                        <Form form={form} layout="vertical">
                            <Form.Item
                                label="Connection name"
                                name="name"
                                extra="Give this connection a label (useful when adding multiple)."
                                rules={[{ required: true, message: "Please enter a name" }]}
                            >
                                <Input />
                            </Form.Item>

                            {isGithub && (
                                <Form.Item label="Deployment type" name="deployment">
                                    <Segmented
                                        block
                                        onChange={handleDeploymentChange}
                                        options={[
                                            { label: "Cloud (github.com)", value: "CLOUD" },
                                            { label: "GitHub Enterprise", value: "ENTERPRISE" },
                                            { label: "Custom", value: "CUSTOM" }
                                        ]}
                                    />
                                </Form.Item>
                            )}

                            <Form.Item
                                label="MCP endpoint URL"
                                name="endpoint"
                                rules={[
                                    { required: true, message: "Please enter the endpoint" },
                                    { type: "url", message: "Enter a valid URL" }
                                ]}
                            >
                                <Input placeholder="https://..." />
                            </Form.Item>

                            <Form.Item label="Transport">
                                <Select
                                    disabled
                                    value="STREAMABLE_HTTP"
                                    suffixIcon={<Lock size={14} />}
                                    options={[
                                        { label: "Streamable HTTP", value: "STREAMABLE_HTTP" }
                                    ]}
                                />
                            </Form.Item>

                            <Form.Item label="Authentication" name="authType">
                                <Select
                                    options={[
                                        { label: "None", value: "NONE" },
                                        { label: "Bearer (personal access token)", value: "BEARER" }
                                    ]}
                                    onChange={(v) => {
                                        if (v === "NONE") form.setFieldValue("secret", undefined);
                                    }}
                                />
                            </Form.Item>

                            {authType === "BEARER" && (
                                <Form.Item
                                    name="secret"
                                    extra={
                                        <span className="secure-note">
                                            <ShieldCheck size={14} />
                                            Credentials are encrypted at rest and never sent to the LLM.
                                        </span>
                                    }
                                    rules={[
                                        { required: true, message: "Please enter the bearer token" }
                                    ]}
                                >
                                    <Input.Password placeholder="Paste your token" />
                                </Form.Item>
                            )}
                        </Form>
                    </div>

                    {step === 1 && result && (
                        <div className={`test-result ${result.ok ? "ok" : "fail"}`}>
                            {result.ok ? <Check size={22} /> : <CircleAlert size={22} />}
                            <div>
                                <h3>
                                    {result.ok ? "Connection successful" : "Connection failed"}
                                </h3>
                                {result.ok ? (
                                    <>
                                        <p>{result.toolCount ?? 0} capabilities found.</p>
                                        <ul>
                                            {result.tools?.slice(0, 6).map((t) => (
                                                <li key={t}>{t}</li>
                                            ))}
                                        </ul>
                                    </>
                                ) : (
                                    <p>
                                        {result.error ??
                                            "Check the endpoint and token, then go back and try again."}
                                    </p>
                                )}
                            </div>
                        </div>
                    )}
                </div>

                {/* Side panel */}
                <aside className="modal-aside">
                    <h4>About this MCP server</h4>
                    <p>
                        {preset?.description ??
                            "Connect any MCP-compatible server over Streamable HTTP."}
                    </p>

                    {preset?.highlights && (
                        <>
                            <h5>What you can do</h5>
                            <ul className="check-list">
                                {preset.highlights.map((h) => (
                                    <li key={h}>
                                        <Check size={14} />
                                        {h}
                                    </li>
                                ))}
                            </ul>
                        </>
                    )}

                    {preset?.requires && (
                        <>
                            <h5>Requires</h5>
                            {preset.requires.map((r) => (
                                <p key={r} className="req">
                                    {r}
                                </p>
                            ))}
                        </>
                    )}
                </aside>
            </div>
        </Modal>
    );
};

export default ToolConnectModal;
