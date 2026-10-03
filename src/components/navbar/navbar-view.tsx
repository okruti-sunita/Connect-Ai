import React, {useState} from "react";
import {useNavigate} from 'react-router-dom';
import {Clock3, GitBranch, Plus} from "lucide-react";
import '../navbar/style/navbar-view.scss';

const NavbarView = () => {
    const [selectedTab, setSelectedTab] = useState("new-investigations");
    const navigate = useNavigate()

    const handleNewInvestigationsButton = () => {
        setSelectedTab("new-investigations")
        navigate('/');
    }

    const handleConnectionsButton = () => {
        setSelectedTab("connections")
        navigate('/connections')
    }

    const handleInvestigations = () => {
        setSelectedTab("investigations")
        navigate('/investigations')
    }

    return (
        <nav className="navbar">

            <div className="navbar-menu">

                <button
                    className={`nav-item ${selectedTab === "new-investigations" ? "active" : ""}`}
                    onClick={handleNewInvestigationsButton}
                >
                    <Plus size={17}/>
                    <span>New Investigation</span>
                </button>

                <button
                    className={`nav-item ${selectedTab === "investigations" ? "active" : ""}`}
                    onClick={handleInvestigations}
                >
                    <Clock3 size={17}/>
                    <span>Investigations</span>
                </button>

                <button
                    className={`nav-item ${selectedTab === "connections" ? "active" : ""}`}
                    onClick={handleConnectionsButton}
                >
                    <GitBranch size={17}/>
                    <span>Connections</span>
                </button>

            </div>
        </nav>
    );
};

export default NavbarView;