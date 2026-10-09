import {useNavigate, useLocation} from "react-router-dom";
import {
    Plus,
    Clock3,
    GitBranch,
    Moon,
    Sun,
} from "lucide-react";
import '../navbar/style/navbar.scss'

type NavbarViewProps = { theme: "light" | "dark"; onToggleTheme: () => void };

const NavbarView = ({theme, onToggleTheme}: NavbarViewProps) => {
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

            <div className="navbar-footer">
                <button className="theme-toggle" type="button" onClick={onToggleTheme} aria-label={`Switch to ${theme === "light" ? "dark" : "light"} theme`}>
                    {theme === "light" ? <Moon size={17}/> : <Sun size={17}/>}
                    <span>{theme === "light" ? "Dark mode" : "Light mode"}</span>
                    <span className={`theme-switch ${theme === "dark" ? "is-dark" : ""}`} aria-hidden="true"><span/></span>
                </button>
            </div>
        </nav>
    );
};

export default NavbarView;