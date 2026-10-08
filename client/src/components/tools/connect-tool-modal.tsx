import {useEffect, useRef, useState} from 'react';
import {Button, Form, Input, Modal, Segmented, Select, message} from 'antd';
import {Check, ShieldCheck} from 'lucide-react';
import './style/connect-tool-modal.scss';
import ToolLogo from './tool-logo';
import {CUSTOM_ITEM, type CatalogItem} from './tool-catalog';
import {toolsApi, ToolsApiError, type Capability, type RegisterToolPayload} from './tools-api';

interface FormValues {
    connectionName: string;
    deployment?: string;
    endpoint: string;
    authType: 'NONE' | 'BEARER';
    token?: string;
}

interface TestState {
    phase: 'running' | 'success' | 'failed';
    stage: number;
    error?: string;
    capabilities: Capability[];
    capabilitiesFailed: boolean;
}

const STAGES = ['Registering the connection', 'Connecting to the server', 'Discovering capabilities'];

interface WizardProps {
    item: CatalogItem;
    onClose: () => void;
    onChanged: () => void;
}

const Wizard = ({item, onClose, onChanged}: WizardProps) => {
    const [form] = Form.useForm<FormValues>();
    const [step, setStep] = useState<1 | 2>(1);
    const [test, setTest] = useState<TestState | null>(null);
    const alive = useRef(true);
    // Remembers the tool created during this session so "Retry" does not register a duplicate.
    const registered = useRef<{ id: string; fingerprint: string } | null>(null);
    const onChangedRef = useRef(onChanged);
    useEffect(() => {
        onChangedRef.current = onChanged;
    });
    useEffect(() => {
        alive.current = true;
        return () => {
            alive.current = false;
        };
    }, []);

    const isCustom = item.id === 'custom';
    const watchedAuth = Form.useWatch('authType', form);
    const authType = isCustom ? (watchedAuth ?? 'NONE') : 'BEARER';
    const endpoint = Form.useWatch('endpoint', form);
    const usingManagedAddress = !isCustom && !!item.endpoint && endpoint === item.endpoint;

    const runTest = async () => {
        const v = form.getFieldsValue(true) as FormValues;
        const payload: RegisterToolPayload = {
            name: v.connectionName.trim(),
            transport: 'STREAMABLE_HTTP',
            endpoint: v.endpoint.trim(),
            authType,
            secret: authType === 'BEARER' ? (v.token ?? '') : null,
            enabled: true,
        };
        const fingerprint = JSON.stringify([payload.name, payload.endpoint, payload.authType, payload.secret]);
        const set = (s: TestState) => {
            if (alive.current) setTest(s);
        };
        let stage = 0;
        setStep(2);
        set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
        try {
            if (!registered.current || registered.current.fingerprint !== fingerprint) {
                const created = await toolsApi.register(payload);
                registered.current = {id: created.id, fingerprint};
                onChangedRef.current(); // the new tool exists on the server now, so show it in the list
            }
            const id = registered.current.id;
            stage = 1;
            set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
            await toolsApi.connect(id);
            if ((await toolsApi.status(id)) !== 'CONNECTED') throw new ToolsApiError('not connected', 0);
            stage = 2;
            set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
            let capabilities: Capability[] = [];
            let capabilitiesFailed = false;
            try {
                capabilities = await toolsApi.capabilities(id);
            } catch {
                capabilitiesFailed = true;
            }
            set({phase: 'success', stage: 3, capabilities, capabilitiesFailed});
            onChangedRef.current();
        } catch (error) {
            console.error('Connection test failed at stage', stage, error instanceof Error ? error.name : error);
            const failureMessage =
                stage === 0 && error instanceof ToolsApiError && error.status !== 0
                    ? error.message
                    : stage === 0
                        ? "Can't reach the Connect AI server. Check that the backend is running."
                        : 'We could not connect to the server. Check the address and token, then try again.';
            set({phase: 'failed', stage, error: failureMessage, capabilities: [], capabilitiesFailed: false});
            onChangedRef.current();
        }
    };

    const handleTest = async () => {
        try {
            await form.validateFields();
        } catch {
            return;
        }
        void runTest();
    };

    const handleFinish = () => {
        message.success(`${form.getFieldValue('connectionName') ?? item.name} is connected.`);
        onClose();
    };

    const initialEndpoint = item.deployments?.[0]?.endpoint ?? item.endpoint;
    const running = test?.phase === 'running';

    return (
        <div className="wizard">
            <header className="wizard-head">
                <ToolLogo name={item.name} item={item} size={52}/>
                <div>
                    <h2>{isCustom ? 'Add a custom MCP server' : `Connect ${item.name}`}</h2>
                    <p>{isCustom ? 'Any server that speaks MCP' : `${item.name} MCP server`}</p>
                </div>
            </header>
            <ol className="wizard-steps" aria-label="Progress">
                <li className={step === 1 ? 'current' : 'done'} aria-current={step === 1 ? 'step' : undefined}>
                    <span>{step === 2 ? <Check size={14}/> : 1}</span>Configure
                </li>
                <li className={step === 2 ? 'current' : ''} aria-current={step === 2 ? 'step' : undefined}>
                    <span>2</span>Test &amp; Save
                </li>
            </ol>

            <div className="wizard-body">
                <section className="wizard-main">
                    <div hidden={step !== 1}>
                        <h3>Configuration</h3>
                        <Form<FormValues>
                            form={form}
                            layout="vertical"
                            requiredMark={false}
                            initialValues={{
                                connectionName: item.defaultConnectionName,
                                deployment: item.deployments?.[0]?.id,
                                endpoint: initialEndpoint,
                                authType: isCustom ? 'NONE' : 'BEARER',
                            }}
                        >
                            <Form.Item
                                label="Connection name"
                                name="connectionName"
                                extra="Give this connection a label (useful when adding more than one)."
                                rules={[{required: true, whitespace: true, message: 'Enter a name'}]}
                            >
                                <Input maxLength={100} autoComplete="off"/>
                            </Form.Item>

                            {item.deployments && (
                                <Form.Item label="Deployment type" name="deployment">
                                    <Segmented
                                        block
                                        options={item.deployments.map((d) => ({label: d.label, value: d.id}))}
                                        onChange={(value) => {
                                            const d = item.deployments?.find((x) => x.id === value);
                                            form.setFieldValue('endpoint', d?.endpoint ?? '');
                                        }}
                                    />
                                </Form.Item>
                            )}

                            <Form.Item
                                label="MCP endpoint URL"
                                name="endpoint"
                                extra={usingManagedAddress ? `Filled in for you. This is the standard ${item.name} address.` : undefined}
                                rules={[
                                    {required: true, message: 'Enter the server address'},
                                    {pattern: /^https?:\/\/\S+$/i, message: 'The address must start with http:// or https://'},
                                ]}
                            >
                                <Input placeholder="https://tools.yourcompany.com/mcp" autoComplete="off"/>
                            </Form.Item>

                            <Form.Item label="Transport">
                                <Select disabled value="STREAMABLE_HTTP" options={[{value: 'STREAMABLE_HTTP', label: 'Streamable HTTP'}]}/>
                            </Form.Item>

                            {isCustom && (
                                <Form.Item label="Authentication" name="authType">
                                    <Select options={[{value: 'NONE', label: 'None'}, {value: 'BEARER', label: 'Bearer token'}]}/>
                                </Form.Item>
                            )}

                            {authType === 'BEARER' && (
                                <Form.Item
                                    label={item.tokenLabel}
                                    name="token"
                                    extra={item.tokenHelp}
                                    rules={[{required: true, whitespace: true, message: 'Paste the token to continue'}]}
                                >
                                    <Input.Password placeholder="Paste your token" autoComplete="new-password"/>
                                </Form.Item>
                            )}
                        </Form>
                        <p className="secure-note"><ShieldCheck size={16}/>Credentials are encrypted at rest and never sent to the LLM.</p>
                    </div>

                    {step === 2 && test && (
                        <div>
                            <h3>Test connection</h3>
                            <ol className="test-list" aria-live="polite">
                                {STAGES.map((label, i) => {
                                    const done = test.stage > i || test.phase === 'success';
                                    const failed = test.phase === 'failed' && test.stage === i;
                                    const active = test.phase === 'running' && test.stage === i;
                                    return (
                                        <li key={label} className={done ? 'done' : failed ? 'failed' : active ? 'active' : ''}>
                                            <span className="test-icon">{done ? <Check size={14}/> : failed ? '!' : ''}</span>
                                            {label}
                                        </li>
                                    );
                                })}
                            </ol>
                            {test.phase === 'failed' && <p className="test-error" role="alert">{test.error}</p>}
                            {test.phase === 'success' && (
                                <div className="test-result">
                                    <p className="test-ok">
                                        <Check size={16}/>
                                        {test.capabilitiesFailed
                                            ? 'Connected, but the capability list could not be loaded. You can view it later.'
                                            : `Connected. ${test.capabilities.length} capabilities found.`}
                                    </p>
                                    {test.capabilities.length > 0 && (
                                        <ul className="cap-preview">
                                            {test.capabilities.slice(0, 6).map((c) => <li key={c.name}><code>{c.name}</code></li>)}
                                            {test.capabilities.length > 6 && <li>+{test.capabilities.length - 6} more</li>}
                                        </ul>
                                    )}
                                </div>
                            )}
                        </div>
                    )}
                </section>

                <aside className="wizard-about">
                    <h3>About this MCP server</h3>
                    <p>{item.about}</p>
                    <h4>What you can do</h4>
                    <ul>{item.canDo.map((c) => <li key={c}><Check size={14}/>{c}</li>)}</ul>
                    <h4>Requires</h4>
                    <ul className="plain">{item.requires.map((r) => <li key={r}>{r}</li>)}</ul>
                </aside>
            </div>

            <footer className="wizard-foot">
                {step === 1 ? (
                    <>
                        <Button onClick={onClose}>Cancel</Button>
                        <Button type="primary" onClick={handleTest}>Test Connection</Button>
                    </>
                ) : (
                    <>
                        <Button disabled={running} onClick={() => setStep(1)}>Back</Button>
                        {test?.phase === 'success' ? (
                            <Button type="primary" onClick={handleFinish}>Save &amp; Finish</Button>
                        ) : (
                            <Button type="primary" disabled={running} loading={running} onClick={() => void runTest()}>
                                {running ? 'Testing' : 'Retry test'}
                            </Button>
                        )}
                    </>
                )}
            </footer>
        </div>
    );
};

interface ToolConnectModalProps {
    open: boolean;
    /** null opens the custom-server form. */
    preset: CatalogItem | null;
    /** Changes on every opening so each opening starts with a clean form and test state. */
    session: number;
    onClose: () => void;
    /** Called whenever the server-side tool list changed (registered, connected or failed). */
    onChanged: () => void;
}

const ToolConnectModal = ({open, preset, session, onClose, onChanged}: ToolConnectModalProps) => (
    <Modal open={open} footer={null} title={null} centered width={900} mask={{closable: false}} destroyOnHidden onCancel={onClose}
           className="tool-wizard-modal" styles={{container: {padding: 0, overflow: 'hidden'}}}>
        {/* antd keeps closed content mounted until the animation ends, so a new key per opening is what resets it. */}
        <Wizard key={`${preset?.id ?? 'custom'}-${session}`} item={preset ?? CUSTOM_ITEM} onClose={onClose} onChanged={onChanged}/>
    </Modal>
);

export default ToolConnectModal;
