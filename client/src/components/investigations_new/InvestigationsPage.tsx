import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import InvestigationList from "./InvestigationList";
import InvestigationDetail from "./InvestigationDetail";
import { listInvestigations, startInvestigation } from "./api";
import { errorText } from "../../api/http";
import type { InvestigationSummary } from "./types";
import { toolsApi } from "../tools/tools-api";
import { findCatalogItem } from "../tools/tools-utils";
import { SUGGESTIONS_BY_TOOL } from "../dashboard/dashboard-view";

const InvestigationsPage = () => {
    const [items, setItems] = useState<InvestigationSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [starting, setStarting] = useState(false);
    const [startError, setStartError] = useState<string | null>(null);
    const startingRef = useRef(false);
    const [examples, setExamples] = useState<string[]>([]);
    useEffect(() => {
        toolsApi.list()
            .then((tools) => {
                const kinds = new Set(tools.filter((t) => t.runtimeConnected)
                    .map((t) => findCatalogItem(t.name)?.id).filter((id): id is string => !!id));
                setExamples([...kinds].flatMap((kind) => SUGGESTIONS_BY_TOOL[kind] ?? []));
            })
            .catch(() => setExamples([]));
    }, []);
    const [searchParams, setSearchParams] = useSearchParams();
    const location = useLocation();
    const navigate = useNavigate();
    const handledNavState = useRef(false);

    // The open investigation lives in the URL (?id=...), so a browser refresh keeps it open.
    const selectedId = searchParams.get("id");
    const open = useCallback((id: string | null) => {
        setSearchParams(id ? { id } : {}, { replace: false });
    }, [setSearchParams]);

    const loadList = useCallback(async () => {
        try {
            setLoading(true);
            setLoadError(null);
            setItems(await listInvestigations());
        } catch (e) {
            setLoadError(errorText(e, "Unable to load investigations."));
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (!selectedId) void loadList();
    }, [selectedId, loadList]);

    const handleStart = useCallback(async (question: string) => {
        if (startingRef.current) return; // guards against duplicate submissions
        startingRef.current = true;
        setStarting(true);
        setStartError(null);
        try {
            const created = await startInvestigation(question);
            setItems((prev) => [created, ...prev.filter((i) => i.id !== created.id)]);
            open(created.id); // the detail view polls until the run reaches a terminal status
        } catch (e) {
            setStartError(errorText(e, "Could not start the investigation."));
        } finally {
            startingRef.current = false;
            setStarting(false);
        }
    }, [open]);

    /* Started from the dashboard: navigate('/investigations', { state: { question } }) */
    useEffect(() => {
        const question = (location.state as { question?: string } | null)?.question;
        if (!question || handledNavState.current) return;
        handledNavState.current = true;
        navigate(location.pathname, { replace: true, state: null }); // don't restart on refresh
        void handleStart(question);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (selectedId) {
        return <InvestigationDetail key={selectedId} id={selectedId} onBack={() => open(null)} />;
    }

    return (
        <InvestigationList
            items={items}
            loading={loading}
            loadError={loadError}
            starting={starting}
            startError={startError}
            onOpen={(i) => open(i.id)}
            onStart={(q) => void handleStart(q)}
            onReload={() => void loadList()}
            examples={examples}
        />
    );
};

export default InvestigationsPage;
