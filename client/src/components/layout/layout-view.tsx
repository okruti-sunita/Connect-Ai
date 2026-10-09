import {useEffect, useState} from "react";
import {Flex, Layout} from "antd";
import Sider from "antd/es/layout/Sider";
import {Content} from "antd/es/layout/layout";
import "../layout/style/layout-view.scss";
import {Outlet} from "react-router-dom";
import NavbarView from "../navbar/navbar-view";

const LayoutView = () => {
    const [theme, setTheme] = useState<"light" | "dark">(() => {
        const savedTheme = localStorage.getItem("connect-ai-theme");
        if (savedTheme === "light" || savedTheme === "dark") return savedTheme;
        return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    });

    useEffect(() => {
        document.documentElement.dataset.theme = theme;
        localStorage.setItem("connect-ai-theme", theme);
    }, [theme]);

    const toggleTheme = () => {
        setTheme((currentTheme) => currentTheme === "light" ? "dark" : "light");
    };

    return (
        <Flex className="connectAI-app-shell">
            <Layout className="connectAI-root-layout">
                <Sider className="connectAI-layout" width={250}>
                    <div className="logo">
                        <div className="badge">C</div>
                        <div className="logo-text">Connect AI</div>
                    </div>
                    <NavbarView theme={theme} onToggleTheme={toggleTheme}/>
                </Sider>

                <Layout className="connectAI-content-layout">
                    <Content><Outlet/></Content>
                </Layout>
            </Layout>
        </Flex>
    );
};

export default LayoutView;