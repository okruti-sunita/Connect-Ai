import {Layout} from "antd";
import {Outlet} from "react-router-dom";
import "../../App.scss";
import NavbarView from "../navbar/navbar-view";

const {Sider, Content} = Layout;

function LayoutView() {
    return (
        <Layout style={{minHeight: "100vh"}}>
            <Sider width={240} className="app-sidebar">
                <div className="app-logo">
                    <div className="logo">
                        C
                    </div>

                    <div className="text">
                        Connect AI
                    </div>
                </div>
                <div className={'navbar'}>
                    <NavbarView/>
                </div>
            </Sider>

            <Layout>
                <Content className="app-content">
                    <Outlet/>
                </Content>
            </Layout>
        </Layout>
    );
}

export default LayoutView;