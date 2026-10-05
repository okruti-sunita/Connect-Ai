import {useNavigate} from "react-router-dom";
import {
    Plus,
    Clock3,
    Star,
    GitBranch,
    Settings,
} from "lucide-react";
import '../navbar/style/navbar.scss'
import {useState} from "react";

const NavbarView = () => {
    const navigate = useNavigate();
    const [selectedTab, setSelectedTab] = useState("new-investigation")

    const handleNewInvestigationsButton = () => {
        setSelectedTab("new-investigation")
        navigate("/");
    };

    const handleConnectionsButton = () => {
        setSelectedTab("connections")
        navigate("/connections");
    };

    const handleInvestigations = () => {
        setSelectedTab("investigations")
        navigate("/investigations");
    };


    return (
        <nav className="navbar">

            {/* Navigation */}
            <div className="navbar-menu">

                {/* New Investigation */}
                <button
                    className={`nav-item ${selectedTab === "new-investigation" ? "active" : ""}`}
                    onClick={handleNewInvestigationsButton}
                >
                    <div style={{display: "flex", gap: "8px", alignItems: "center"}}>
                        <div>
                            <Plus size={17}/>
                        </div>
                        <div>
                            New Investigation
                        </div>
                    </div>


                </button>


                {/* Connections */}
                <button
                    className={`nav-item ${selectedTab === "connections" ? "active" : ""}`}
                    onClick={handleConnectionsButton}
                >
                    <GitBranch size={17}/>
                    <span>Connections</span>
                </button>

                {/* Investigations */}
                <button
                    className={`nav-item ${selectedTab === "investigations" ? "active" : ""}`}
                    onClick={handleInvestigations}
                >
                    <Clock3 size={17}/>
                    <span>Investigations</span>
                </button>


            </div>
        </nav>
    );
};

export default NavbarView;