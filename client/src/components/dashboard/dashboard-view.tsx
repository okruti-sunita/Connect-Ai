import { Layout } from "antd";
import { Outlet } from "react-router-dom";
import "../../App.css";

const { Sider, Content } = Layout;

function DashboardView() {
    return (
        <Layout style={{ minHeight: "100vh" }}>
            <Sider width={240} className="app-sidebar">
                <div className="app-logo">
                    <div className="logo">
                        C
                    </div>

                    <div className="text">
                        Connect AI
                    </div>
                </div>
            </Sider>

            <Layout>
                <Content className="app-content">
                    <Outlet />
                </Content>
            </Layout>
        </Layout>
    );
}

export default DashboardView;