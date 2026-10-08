import {useEffect, useRef, useState} from 'react';
import {Button, Checkbox, Input, Select} from 'antd';
import {Play} from 'lucide-react';
import {argumentSkeleton, executeTool, mayChangeData, parseArguments, type CapabilityDetail, type ExecutionOutcome} from './tool-detail-api';

interface RunFormProps {
    toolId: string;
    capability: CapabilityDetail;
}

/** Keyed by tool name in the parent, so choosing another tool starts with fresh arguments. */
const RunForm = ({toolId, capability}: RunFormProps) => {
    const [args, setArgs] = useState(() => argumentSkeleton(capability));
    const [confirmed, setConfirmed] = useState(false);
    const [running, setRunning] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [outcome, setOutcome] = useState<ExecutionOutcome | null>(null);
    const controller = useRef<AbortController | null>(null);
    useEffect(() => () => controller.current?.abort(), []);

    const risky = mayChangeData(capability);

    const run = async () => {
        const parsed = parseArguments(args);
        if (!parsed.ok) {
            setError(parsed.error);
            return;
        }
        setError(null);
        setOutcome(null);
        setRunning(true);
        controller.current?.abort();
        const c = new AbortController();
        controller.current = c;
        try {
            setOutcome(await executeTool(toolId, capability.name, parsed.value, c.signal));
        } catch {
            // aborted because the panel was closed or changed
        } finally {
            if (controller.current === c) setRunning(false);
        }
    };

    return (
        <>
            {capability.description && <p className="mut small">{capability.description}</p>}
            <label className="field-label" htmlFor="test-args">Arguments (JSON)</label>
            <Input.TextArea id="test-args" className="mono" rows={4} value={args} spellCheck={false}
                            onChange={(e) => setArgs(e.target.value)}/>
            {error && <p className="field-error" role="alert">{error}</p>}
            {risky && (
                <Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} className="risk-check">
                    This tool may change data in the connected system. Run it anyway.
                </Checkbox>
            )}
            <Button type="primary" block icon={<Play size={14}/>} loading={running} disabled={risky && !confirmed} onClick={() => void run()}>
                Run
            </Button>
            {outcome && (
                <div className={`run-out ${outcome.ok ? 'ok' : 'bad'}`} role="status">
                    <strong>{outcome.ok ? 'Done' : 'Failed'} in {outcome.ms} ms</strong>
                    <pre>{outcome.text}</pre>
                </div>
            )}
        </>
    );
};

interface ToolTestPanelProps {
    toolId: string;
    items: CapabilityDetail[];
    selected: string | null;
    onSelect: (name: string) => void;
}

const ToolTestPanel = ({toolId, items, selected, onSelect}: ToolTestPanelProps) => {
    const capability = items.find((c) => c.name === selected);
    return (
        <section className="side-card" aria-label="Try a tool">
            <h3>Try a tool</h3>
            <p className="mut small">Run one tool against the connected system to see what comes back.</p>
            <label className="field-label" htmlFor="test-tool">Tool</label>
            <Select id="test-tool" showSearch style={{width: '100%'}} value={selected ?? undefined} placeholder="Choose a tool"
                    onChange={onSelect} options={items.map((c) => ({value: c.name, label: c.name}))}/>
            {capability ? <RunForm key={capability.name} toolId={toolId} capability={capability}/> :
                <p className="mut small">Pick a tool to begin.</p>}
        </section>
    );
};

export default ToolTestPanel;
