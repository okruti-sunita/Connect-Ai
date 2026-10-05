import {Flex, Layout} from "antd";
import Sider from "antd/es/layout/Sider";
import {Content} from "antd/es/layout/layout";
import "../layout/style/layout-view.scss";
import {Outlet} from "react-router-dom";
import NavbarView from "../navbar/navbar-view";

const LayoutView = () => {
    return (
        <Flex>
            <Layout>
                <Sider className="connectAI-layout" width={250}>
                    <div className="logo">
                        <div className="badge">C</div>
                        <div className="logo-text">Connect AI</div>
                    </div>
                    <NavbarView/>
                </Sider>

                <Layout>
                    <Content><Outlet/></Content>
                </Layout>
            </Layout>
        </Flex>
    );
};

export default LayoutView;