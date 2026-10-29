import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Customers from './pages/Customers'
import CustomerDetail from './pages/CustomerDetail'
import Products from './pages/Products'
import Policies from './pages/Policies'
import PolicyDetail from './pages/PolicyDetail'
import Billing from './pages/Billing'
import Claims from './pages/Claims'
import ClaimDetail from './pages/ClaimDetail'
import Workflow from './pages/Workflow'
import Lifecycle from './pages/Lifecycle'
import Reports from './pages/Reports'
import Reconciliation from './pages/Reconciliation'
import Notifications from './pages/Notifications'
import Batch from './pages/Batch'
import Admin from './pages/Admin'

export default function App() {
  const { user } = useAuth()
  if (!user) {
    return (
      <Routes>
        <Route path="*" element={<Login />} />
      </Routes>
    )
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="customers" element={<Customers />} />
        <Route path="customers/:id" element={<CustomerDetail />} />
        <Route path="products" element={<Products />} />
        <Route path="policies" element={<Policies />} />
        <Route path="policies/:id" element={<PolicyDetail />} />
        <Route path="billing" element={<Billing />} />
        <Route path="claims" element={<Claims />} />
        <Route path="claims/:id" element={<ClaimDetail />} />
        <Route path="workflow" element={<Workflow />} />
        <Route path="lifecycle" element={<Lifecycle />} />
        <Route path="reports" element={<Reports />} />
        <Route path="reconciliation" element={<Reconciliation />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="batch" element={<Batch />} />
        <Route path="admin" element={<Admin />} />
        <Route path="*" element={<Navigate to="/" />} />
      </Route>
    </Routes>
  )
}
