import {BrowserRouter, Routes, Route} from "react-router-dom";
import LayoutView from "./components/layout/layout-view";
import DashboardView from "./components/dashboard/dashboard-view";
import ToolsView from "./components/tools/tools-view";
import InvestigationsPage from "./components/investigations_new/InvestigationsPage";

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<LayoutView/>}>
                    <Route index element={<DashboardView/>}/>
                    <Route path='connections' element={<ToolsView/>}/>
                    <Route path='investigations' element={<InvestigationsPage/>}/>
                </Route>
            </Routes>
        </BrowserRouter>
    );
}

export default App;