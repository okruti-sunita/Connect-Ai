import {BrowserRouter, Routes, Route} from "react-router-dom";
import LayoutView from "./components/layout/layout-view";
import DashboardView from "./components/dashboard/dashboard-view";
import ToolsView from "./components/tools/tools-view";
import InvestigationView from "./components/investigations/investigations-view";

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<LayoutView/>}>
                    <Route index element={<DashboardView/>}/>
                    <Route path='connections' element={<ToolsView/>}/>
                    <Route path='investigations' element={<InvestigationView/>}/>
                </Route>
            </Routes>
        </BrowserRouter>
    );
}

export default App;