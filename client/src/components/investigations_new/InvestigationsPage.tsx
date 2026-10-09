import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { message } from "antd";
import InvestigationList from "./InvestigationList";
import InvestigationDetail from "./InvestigationDetail";
import { listInvestigations, startInvestigation } from "./api";
import type { InvestigationSummary } from "./types";

const InvestigationsPage = () => {
    const [items, setItems] = useState<InvestigationSummary[]>([]);
    const [loading, setLoading] = useState(true);
    const [starting, setStarting] = useState(false);
    const [selectedId, setSelectedId] = useState<string | null>(null);
    const location = useLocation();
    const navigate = useNavigate();
    const handledNavState = useRef(false);

    const loadList = async () => {
        try {
            setLoading(true);
            setItems(await listInvestigations());
        } catch (e) {
            console.error("Error while loading investigations:", e);
            message.error("Unable to load investigations.");
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        loadList();
    }, []);

    const handleStart = async (question: string) => {
        try {
            setStarting(true);
            const created = await startInvestigation(question);
            setItems((prev) => [created, ...prev]);
            setSelectedId(created.id);
        } catch (e) {
            console.error("Error while starting investigation:", e);
            message.error("Could not start the investigation.");
        } finally {
            setStarting(false);
        }
    };

    /* Started from the dashboard: navigate('/investigations', { state: { question } }) */
    useEffect(() => {
        const question = (location.state as { question?: string } | null)?.question;
        if (!question || handledNavState.current) return;
        handledNavState.current = true;
        navigate(location.pathname, { replace: true, state: null }); // don't restart on refresh
        handleStart(question);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    if (selectedId) {
        return <InvestigationDetail key={selectedId} id={selectedId} onBack={() => setSelectedId(null)} />;
    }

    return (
        <InvestigationList
            items={items}
            loading={loading}
            starting={starting}
            onOpen={(i) => setSelectedId(i.id)}
            onStart={handleStart}
        />
    );
};

export default InvestigationsPage;
