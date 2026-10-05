import {BrowserRouter, Routes, Route} from "react-router-dom";
import LayoutView from "./components/layout/layout-view";
import DashboardView from "./components/dashboard/dashboard-view";
import ConnectionView from "./components/connections/connection-view";
import InvestigationView from "./components/investigations/investigations-view";

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<LayoutView/>}>
                    <Route index element={<DashboardView/>}/>
                    <Route path='connections' element={<ConnectionView/>}/>
                    <Route path='investigations' element={<InvestigationView/>}/>
                </Route>
            </Routes>
        </BrowserRouter>
    );
}

export default App;