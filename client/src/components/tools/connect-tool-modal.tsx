import {useEffect, useRef, useState} from 'react';
import {Button, Form, Input, Modal, Segmented, Select, message} from 'antd';
import {Check, ShieldCheck} from 'lucide-react';
import './style/connect-tool-modal.scss';
import ToolLogo from './tool-logo';
import type {CatalogItem, ConnectMethod} from './tool-catalog';
import {toolsApi, ToolsApiError, type Capability, type CreateConnectionPayload, type OAuthProviderDto, type RegisterToolPayload} from './tools-api';
import {openSignInPopup, signInWithPopup} from './oauth';

interface FormValues {
    connectionName: string;
    deployment?: string;
    endpoint: string;
    connectionMethod: ConnectMethod;
    /** For tools that support OAuth: sign in with the provider, or paste a token. */
    authMethod?: 'oauth' | 'token';
    username?: string;
    token?: string;
    headerName?: string;
    headerValue?: string;
    headerName2?: string;
    headerValue2?: string;
}

interface TestState {
    phase: 'running' | 'success' | 'failed';
    stage: number;
    error?: string;
    capabilities: Capability[];
    capabilitiesFailed: boolean;
}

const TOKEN_STAGES = ['Testing the endpoint', 'Initializing MCP', 'Discovering tools', 'Saving the connection'];
const oauthStages = (provider: string) =>
    ['Registering the connection', `Signing in with ${provider}`, 'Connecting to the server', 'Discovering capabilities'];

interface WizardProps {
    item: CatalogItem;
    /** Names already used by saved connections (names must be unique). */
    existingNames: string[];
    onClose: () => void;
    onChanged: () => void;
}

/** "GitHub - Main", then "GitHub - 2", "GitHub - 3"... so a second account gets a free name. */
export function uniqueConnectionName(base: string, existingNames: string[]): string {
    const taken = new Set(existingNames.map((n) => n.trim().toLowerCase()));
    if (!base || !taken.has(base.toLowerCase())) return base;
    const stem = base.replace(/\s*-\s*Main$/i, '');
    for (let i = 2; ; i++) {
        const candidate = `${stem} - ${i}`;
        if (!taken.has(candidate.toLowerCase())) return candidate;
    }
}

const Wizard = ({item, existingNames, onClose, onChanged}: WizardProps) => {
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
    const externalMcpRequired = item.connectorMode === 'external-mcp-required';
    const watchedMethod = Form.useWatch('authMethod', form);
    const watchedConnectionMethod = Form.useWatch('connectionMethod', form);
    // null while loading; true/false once the server said whether the provider is configured.
    const [oauthReady, setOauthReady] = useState<boolean | null>(item.oauthProvider ? null : false);
    const [oauthProvider, setOauthProvider] = useState<OAuthProviderDto | null>(null);
    useEffect(() => {
        if (!item.oauthProvider) return;
        let alive = true;
        toolsApi.oauthProviders()
            .then((providers) => {
                if (!alive) return;
                const ready = providers.some((p) => p.id === item.oauthProvider && p.configured);
                setOauthProvider(providers.find((p) => p.id === item.oauthProvider) ?? null);
                setOauthReady(ready);
                if (!ready) form.setFieldValue('authMethod', item.methods.some((method) => method.id === 'TOKEN_BEARER' && method.supported) ? 'token' : 'oauth');
            })
            .catch(() => {
                if (alive) setOauthReady(false);
            });
        return () => {
            alive = false;
        };
    }, [item.oauthProvider, form]);
    const defaultMethod = item.methods.find((method) => method.supported)?.id ?? 'NONE';
    const connectMethod: ConnectMethod = isCustom || externalMcpRequired || !item.oauthProvider
        ? watchedConnectionMethod ?? defaultMethod
        : (watchedMethod ?? 'oauth') === 'oauth' ? 'OAUTH_APP' : 'TOKEN_BEARER';
    const authType: RegisterToolPayload['authType'] = connectMethod === 'OAUTH_APP' || connectMethod === 'OAUTH_DISCOVERY'
        ? 'OAUTH' : connectMethod === 'NONE' ? 'NONE' : 'BEARER';
    const stages = authType === 'OAUTH' ? oauthStages(item.name) : TOKEN_STAGES;
    const endpoint = Form.useWatch('endpoint', form);
    const usingManagedAddress = !isCustom && !!item.endpoint && endpoint === item.endpoint;
    const oauthAlternative = item.methods.some((method) => method.id === 'TOKEN_BEARER' && method.supported);

    const runTest = async (popup: Window | null) => {
        const v = form.getFieldsValue(true) as FormValues;
        const payload: RegisterToolPayload = {
            name: v.connectionName.trim(),
            transport: 'STREAMABLE_HTTP',
            endpoint: v.endpoint.trim(),
            authType,
            secret: authType === 'BEARER' ? (v.token ?? '') : null,
            enabled: true,
            oauthProvider: authType === 'OAUTH' ? item.oauthProvider : null,
            catalogKey: item.key,
        };
        const fingerprint = JSON.stringify([payload.name, payload.endpoint, payload.authType, payload.secret]);
        const set = (s: TestState) => {
            if (alive.current) setTest(s);
        };
        let stage = 0;
        setStep(2);
        set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
        try {
            if (authType === 'OAUTH') {
                if (!registered.current || registered.current.fingerprint !== fingerprint) {
                    const created = await toolsApi.register(payload);
                    registered.current = {id: created.id, fingerprint};
                }
                const id = registered.current.id;
                stage = 1;
                set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
                // The backend exchanges the code and stores the token; this only waits for the outcome.
                const signIn = await signInWithPopup(id, popup);
                if (!signIn.ok) throw new ToolsApiError(signIn.message, 401);
                stage = 2;
                set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
                const connection = await toolsApi.connect(id);
                if (!connection?.initialized) {
                    throw new ToolsApiError(connection?.error ?? 'The MCP server did not complete the handshake.', 502);
                }
                stage = 3;
                set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
                const capabilities = await toolsApi.capabilities(id);
                set({phase: 'success', stage: stages.length, capabilities, capabilitiesFailed: false});
            } else {
                const credentials: Record<string, string> = {};
                if (connectMethod === 'TOKEN_BEARER') credentials.token = v.token ?? '';
                if (connectMethod === 'TOKEN_BASIC') {
                    credentials.username = v.username ?? '';
                    credentials.token = v.token ?? '';
                }
                if (connectMethod === 'TOKEN_HEADER') {
                    credentials.headerName = v.headerName ?? '';
                    credentials.token = v.headerValue ?? '';
                }
                if (connectMethod === 'TOKEN_HEADERS') {
                    if (v.headerName) credentials[`header:${v.headerName}`] = v.headerValue ?? '';
                    if (v.headerName2) credentials[`header:${v.headerName2}`] = v.headerValue2 ?? '';
                }
                const connectionPayload: CreateConnectionPayload = {
                    catalogKey: item.key,
                    name: payload.name,
                    endpoint: payload.endpoint,
                    method: connectMethod,
                    credentials,
                };
                const tested = await toolsApi.testConnection(connectionPayload);
                if (!tested.success) {
                    throw new ToolsApiError(tested.hint ? `${tested.message ?? 'Connection test failed.'} ${tested.hint}` : tested.message ?? 'Connection test failed.', 502);
                }
                stage = 3;
                set({phase: 'running', stage, capabilities: [], capabilitiesFailed: false});
                const created = await toolsApi.createConnection(connectionPayload);
                const capabilities = created.toolNames.map((name) => ({name, description: ''}));
                set({phase: 'success', stage: stages.length, capabilities, capabilitiesFailed: false});
            }
            onChangedRef.current();
        } catch (error) {
            console.error('Connection test failed at stage', stage, error instanceof Error ? error.name : error);
            // Show the backend's explanation (bad URL, refused connection, 401 token, ...).
            const failureMessage = error instanceof ToolsApiError
                ? error.message
                : 'We could not connect to the server. Check the address and token, then try again.';
            set({phase: 'failed', stage, error: failureMessage, capabilities: [], capabilitiesFailed: false});
            onChangedRef.current();
        }
    };

    const handleTest = async () => {
        // The popup must be opened synchronously in the click, before any await, or it is blocked.
        const popup = authType === 'OAUTH' ? openSignInPopup() : null;
        try {
            await form.validateFields();
        } catch {
            popup?.close();
            return;
        }
        void runTest(popup);
    };

    const handleFinish = () => {
        message.success(`${form.getFieldValue('connectionName') ?? item.name} is connected.`);
        onClose();
    };

    const initialEndpoint = item.deployments?.[0]?.endpoint ?? item.defaultEndpoint;
    const running = test?.phase === 'running';
    const methodSelector = isCustom || externalMcpRequired || (!item.oauthProvider
        && item.methods.filter((method) => method.supported).length > 1);
    const renderCredentialField = (name: string, required: boolean) => {
        const field = item.fields.find((candidate) => candidate.name === name);
        if (!field) return null;
        const input = field.type === 'password'
            ? <Input.Password placeholder={field.placeholder} autoComplete="new-password"/>
            : <Input placeholder={field.placeholder} autoComplete="off"/>;
        return <Form.Item key={name} label={field.label} name={name} extra={field.helpText}
                          rules={required ? [{required: true, whitespace: true, message: `Enter ${field.label.toLowerCase()}`}] : undefined}>
            {input}
        </Form.Item>;
    };

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
                                connectionName: uniqueConnectionName(item.defaultConnectionName, existingNames),
                                deployment: item.deployments?.[0]?.id,
                                endpoint: initialEndpoint,
                                connectionMethod: defaultMethod,
                                authMethod: item.oauthProvider ? 'oauth' : 'token',
                            }}
                        >
                            <Form.Item
                                label="Connection name"
                                name="connectionName"
                                extra="Give this connection a label (useful when adding more than one)."
                                rules={[
                                    {required: true, whitespace: true, message: 'Enter a name'},
                                    {
                                        validator: (_, value?: string) => {
                                            const name = (value ?? '').trim().toLowerCase();
                                            // The connection this dialog already registered (Test, then Back) keeps its name.
                                            const own = registered.current
                                                ? String((JSON.parse(registered.current.fingerprint) as unknown[])[0] ?? '').trim().toLowerCase()
                                                : null;
                                            const clash = name !== own && existingNames.some((n) => n.trim().toLowerCase() === name);
                                            return clash
                                                ? Promise.reject(new Error('A connection with this name already exists. Use a different name, e.g. "GitHub - Work".'))
                                                : Promise.resolve();
                                        },
                                    },
                                ]}
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
                                <Input placeholder="https://tools.yourcompany.com/mcp" autoComplete="off"
                                       disabled={!item.endpointEditable}/>
                            </Form.Item>

                            <Form.Item label="Transport">
                                <Select disabled value="STREAMABLE_HTTP" options={[{value: 'STREAMABLE_HTTP', label: 'Streamable HTTP'}]}/>
                            </Form.Item>

                            {methodSelector && (
                                <Form.Item label="Authentication" name="connectionMethod">
                                    <Select options={item.methods.filter((method) => method.supported)
                                        .map((method) => ({value: method.id, label: method.label}))}/>
                                </Form.Item>
                            )}

                            {!isCustom && item.oauthProvider && (
                                <Form.Item label="How should Connect AI access it?" name="authMethod"
                                           extra={oauthReady === false && !oauthAlternative
                                               ? `Sign-in with ${item.name} is not configured on this server yet. Ask the administrator to configure the OAuth client.`
                                               : oauthReady === false
                                                   ? `Sign-in with ${item.name} is not configured on this server yet. An access token is available as an alternative.`
                                               : authType === 'OAUTH'
                                                   ? `Requested access: ${oauthProvider?.scopes?.join(', ') || 'provider-defined scopes'}. A ${item.name} window opens; approve access there. No token to copy.`
                                                   : undefined}>
                                    <Segmented
                                        block
                                        options={[
                                            ...(item.methods.some((method) => method.id === 'OAUTH_APP' && method.supported)
                                                ? [{label: `Sign in with ${item.name}`, value: 'oauth', disabled: oauthReady !== true}] : []),
                                            ...(oauthAlternative ? [{label: 'Access token', value: 'token'}] : []),
                                        ]}
                                    />
                                </Form.Item>
                            )}

                            {!isCustom && item.localServerHelp && (
                                <p className="secure-note">{item.localServerHelp}</p>
                            )}

                            {connectMethod === 'TOKEN_BEARER' && renderCredentialField('token', true)}
                            {connectMethod === 'TOKEN_BASIC' && <>
                                {renderCredentialField('username', true)}
                                {renderCredentialField('token', true)}
                            </>}
                            {connectMethod === 'TOKEN_HEADER' && <>
                                {renderCredentialField('headerName', true)}
                                {renderCredentialField('headerValue', true)}
                            </>}
                            {connectMethod === 'TOKEN_HEADERS' && <>
                                {renderCredentialField('headerName', true)}
                                {renderCredentialField('headerValue', true)}
                                {renderCredentialField('headerName2', false)}
                                {renderCredentialField('headerValue2', false)}
                            </>}
                        </Form>
                        <p className="secure-note"><ShieldCheck size={16}/>Credentials are encrypted at rest and never sent to the LLM.</p>
                    </div>

                    {step === 2 && test && (
                        <div>
                            <h3>Test connection</h3>
                            <ol className="test-list" aria-live="polite">
                                {stages.map((label, i) => {
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
                {item.connectorMode === 'external-mcp-required' ? (
                    <>
                        <p>{item.name} does not have a verified, managed MCP endpoint configured in this application. Provide the endpoint of an MCP server for this service. Connect AI will only show capabilities actually returned by that server.</p>
                        <h4>Required</h4>
                        <ul className="plain"><li>A reachable Streamable HTTP MCP endpoint</li><li>A supported bearer token if that MCP server requires one</li></ul>
                    </>
                ) : (
                    <>
                        <p>{item.permissionsText}</p>
                        <h4>What you can do</h4>
                        <ul>{item.scopes.map((scope) => <li key={scope}><Check size={14}/>{scope}</li>)}</ul>
                        <h4>Requires</h4>
                        <ul className="plain">{item.requires.map((r) => <li key={r}>{r}</li>)}</ul>
                    </>
                )}
                <details className="connector-help">
                    <summary>Connection instructions</summary>
                    <ol>{item.help.steps.map((step) => <li key={step}>{step}</li>)}</ol>
                    {item.help.docsUrl && <a href={item.help.docsUrl} target="_blank" rel="noreferrer">Open documentation</a>}
                </details>
                </aside>
            </div>

            <footer className="wizard-foot">
                {step === 1 ? (
                    <>
                        <Button onClick={onClose}>Cancel</Button>
                        <Button type="primary" disabled={authType === 'OAUTH' && oauthReady !== true} onClick={handleTest}>Test Connection</Button>
                    </>
                ) : (
                    <>
                        <Button disabled={running} onClick={() => setStep(1)}>Back</Button>
                        {test?.phase === 'success' ? (
                            <Button type="primary" onClick={handleFinish}>Save &amp; Finish</Button>
                        ) : (
                            <Button type="primary" disabled={running} loading={running} onClick={() => void runTest(authType === 'OAUTH' ? openSignInPopup() : null)}>
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
    preset: CatalogItem;
    /** Changes on every opening so each opening starts with a clean form and test state. */
    session: number;
    /** Names already used by saved connections. */
    existingNames: string[];
    onClose: () => void;
    /** Called whenever the server-side tool list changed (registered, connected or failed). */
    onChanged: () => void;
}

const ToolConnectModal = ({open, preset, session, existingNames, onClose, onChanged}: ToolConnectModalProps) => (
    <Modal open={open} footer={null} title={null} centered width={900} mask={{closable: false}} destroyOnHidden onCancel={onClose}
           className="tool-wizard-modal" styles={{container: {padding: 0, overflow: 'hidden'}}}>
        {/* antd keeps closed content mounted until the animation ends, so a new key per opening is what resets it. */}
        <Wizard key={`${preset.id}-${session}`} item={preset}
                existingNames={existingNames} onClose={onClose} onChanged={onChanged}/>
    </Modal>
);

export default ToolConnectModal;
