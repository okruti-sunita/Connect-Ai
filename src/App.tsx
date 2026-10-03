import React from "react";
import {BrowserRouter, Routes, Route} from "react-router-dom";
import LayoutView from "./components/layout/layout-view";
import DashboardView from "./components/dashboard/dashboard-view";
import ConnectionsView from "./components/connections/connections-view";
import InvestigationsView from "./components/investigations/investigations-view";

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<LayoutView/>}>
                    // In React Router, index means "show this component at the parent route's default URL."
                    <Route index element={<DashboardView/>}/>
                    <Route path='connections' element={<ConnectionsView/>}/>
                    <Route path='investigations' element={<InvestigationsView/>}/>
                </Route>
            </Routes>
        </BrowserRouter>
    )
}

export default App;