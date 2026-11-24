import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth.jsx'
import Layout from './components/Layout.jsx'
import Login from './pages/Login.jsx'
import Overview from './pages/Overview.jsx'
import MachineDetail from './pages/MachineDetail.jsx'
import Alerts from './pages/Alerts.jsx'
import Maintenance from './pages/Maintenance.jsx'
import Models from './pages/Models.jsx'
import SimControl from './pages/SimControl.jsx'
import Audit from './pages/Audit.jsx'
import Users from './pages/Users.jsx'

export default function App() {
  const { session, canWrite, isAdmin } = useAuth()
  const staff = (el) => (canWrite ? el : <Navigate to="/" replace />)
  if (!session) return <Login />
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Overview />} />
        <Route path="machines/:id" element={<MachineDetail />} />
        <Route path="alerts" element={<Alerts />} />
        <Route path="maintenance" element={<Maintenance />} />
        <Route path="models" element={staff(<Models />)} />
        <Route path="simulator" element={staff(<SimControl />)} />
        <Route path="audit" element={staff(<Audit />)} />
        <Route path="users" element={isAdmin ? <Users /> : <Navigate to="/" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}
