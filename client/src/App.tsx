import {BrowserRouter, Routes, Route} from "react-router-dom";
import DashboardView from "./components/dashboard/dashboard-view";

function App() {
    return (
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<DashboardView />}/>
            </Routes>
        </BrowserRouter>
    )
}

export default App;