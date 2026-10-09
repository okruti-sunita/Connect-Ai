import {useNavigate, useLocation} from "react-router-dom";
import {
    Plus,
    Clock3,
    GitBranch,
    Moon,
    Sun,
    ChevronRight,
} from "lucide-react";
import '../navbar/style/navbar.scss'
import '../navbar/style/sidebar.scss'

type NavbarViewProps = { theme: "light" | "dark"; onToggleTheme: () => void };

// Change these to show the signed-in user in the sidebar footer.
const CURRENT_USER = {name: "Sunita Ghangas", role: "Software Engineer"};

const NAV_ITEMS = [
    {path: "/", label: "New Investigation", icon: Plus},
    {path: "/connections", label: "Connections", icon: GitBranch},
    {path: "/investigations", label: "Investigations", icon: Clock3},
];

const getInitials = (name: string) =>
    name
        .split(" ")
        .filter(Boolean)
        .slice(0, 2)
        .map((part) => part[0].toUpperCase())
        .join("");

const NavbarView = ({theme, onToggleTheme}: NavbarViewProps) => {
    const navigate = useNavigate();
    const location = useLocation();
    const isDark = theme === "dark";

    return (
        <nav className="navbar sb">
            <div className="sb-label">Workspace</div>

            <div className="sb-menu">
                {NAV_ITEMS.map(({path, label, icon: Icon}) => (
                    <button
                        key={path}
                        type="button"
                        className={`sb-item ${location.pathname === path ? "active" : ""}`}
                        aria-current={location.pathname === path ? "page" : undefined}
                        onClick={() => navigate(path)}
                    >
                        <span className="sb-icon"><Icon size={16}/></span>
                        <span className="sb-text">{label}</span>
                        <ChevronRight className="sb-chevron" size={15}/>
                    </button>
                ))}
            </div>

            <div className="sb-footer">
                <button
                    type="button"
                    role="switch"
                    aria-checked={isDark}
                    aria-label={`Switch to ${isDark ? "light" : "dark"} theme`}
                    className="sb-theme"
                    onClick={onToggleTheme}
                >
                    <span className="sb-thumb" aria-hidden="true"/>
                    <span className="sb-opt sb-opt-light"><Sun size={14}/>Light</span>
                    <span className="sb-opt sb-opt-dark"><Moon size={14}/>Dark</span>
                </button>

                {/*<div className="sb-user">*/}
                {/*    <div className="sb-avatar">{getInitials(CURRENT_USER.name)}</div>*/}
                {/*    <div className="sb-user-info">*/}
                {/*        <div className="sb-user-name">{CURRENT_USER.name}</div>*/}
                {/*        <div className="sb-user-role">{CURRENT_USER.role}</div>*/}
                {/*    </div>*/}
                {/*</div>*/}
            </div>
        </nav>
    );
};

export default NavbarView;