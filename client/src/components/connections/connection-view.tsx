import {useEffect, useState} from "react";
import {Plus} from "lucide-react";
import '../connections/style/connection.scss'
import ToolConnectModal from "./connect-tools-modal";
import {message} from "antd";

const ConnectionView = () => {
    const [open, setOpen] = useState(false);
    const [registeredTools, setRegisteredTools] = useState([])

    const handleConnectToolsButton = () => {
        setOpen(true);
    };
    const handleCloseModal = () => {
        setOpen(false);
    };

    async function fetchRegisteredTools() {
        try {
            const response = await fetch(
                'http://localhost:8080/connect-ai/api/tools'
            );
            const data = await response.json();
            setRegisteredTools(data);
            console.log("data:", data);
        } catch (error) {
            console.error("Error while fetching data:", error);
        }
    }

    async function handleConnectButton(id: string) {
        try {
            const response = await fetch(`http://localhost:8080/connect-ai/api/tools/${id}/connect`,
                {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    },
                    // body: JSON.stringify(payload)
                })
            if (!response.ok) {
                throw new Error(`API failed with status: ${response.status}`);
            }

            message.success("MCP server connected successfully!")
            await fetchRegisteredTools();
        } catch (e) {
            console.log("Error while connecting to an MCP server", e);
            message.error("Failed to connect MCP server!")
        }
    }

    useEffect(() => {
        fetchRegisteredTools();
    }, []);

    return (
        <div className="connections-container">
            <div className={'connections-header'}>
                <div className="header">
                    <h1 className="title">
                        Tools & Connections
                    </h1>

                    <p className="sub-title">
                        Connect the engineering systems your organization uses
                    </p>

                </div>
                <div>
                    <div className={'connect-tools-button'}>
                        <div>
                            <Plus size={17}/>
                        </div>
                        <div onClick={handleConnectToolsButton}>
                            Connect Tools
                        </div>
                    </div>
                </div>
            </div>

            <div className={'registered-tools-container'}>
                {registeredTools.map((tool) => (
                    <div key={tool.id} className="card">
                        <div className={'card-header'}>
                            <div>
                                <h3>{tool.name}</h3>
                            </div>
                            <div>
                                <p className={`tool-status ${tool.status}`}>{tool.status}</p>
                            </div>
                        </div>
                        <div className={'card-button-container'}>
                            <button className={'card-button connect'} onClick={() => handleConnectButton(tool.id)}>
                                Connect
                            </button>
                            <button className={'card-button disconnect'} disabled={tool.status === "REGISTERED"}>
                                Disconnect
                            </button>
                        </div>

                    </div>
                ))}
            </div>
            <ToolConnectModal
                open={open}
                onClose={handleCloseModal}
                onToolRegistered={fetchRegisteredTools}
            />
        </div>
    )
}

export default ConnectionView;