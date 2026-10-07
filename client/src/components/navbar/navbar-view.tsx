import {useNavigate, useLocation} from "react-router-dom";
import {
    Plus,
    Clock3,
    GitBranch,
} from "lucide-react";
import '../navbar/style/navbar.scss'

const NavbarView = () => {
    const navigate = useNavigate();
    const location = useLocation();

    const handleNewInvestigationsButton = () => {
        navigate("/");
    };

    const handleConnectionsButton = () => {
        navigate("/connections");
    };

    const handleInvestigations = () => {
        navigate("/investigations");
    };


    return (
        <nav className="navbar">

            {/* Navigation */}
            <div className="navbar-menu">

                {/* New Investigation */}
                <button
                    className={`nav-item ${location.pathname === "/" ? "active" : ""}`}
                    onClick={handleNewInvestigationsButton}
                >
                    <Plus size={17}/>
                    <span>New Investigation</span>
                </button>

                {/* Connections */}
                <button
                    className={`nav-item ${location.pathname === "/connections" ? "active" : ""}`}
                    onClick={handleConnectionsButton}
                >
                    <GitBranch size={17}/>
                    <span>Connections</span>
                </button>

                {/* Investigations */}
                <button
                    className={`nav-item ${location.pathname === "/investigations" ? "active" : ""}`}
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