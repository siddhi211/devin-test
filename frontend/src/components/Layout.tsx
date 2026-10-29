import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth'

export interface NavItem {
  to: string
  label: string
  roles?: string[]
  section?: string
}

export const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard' },
  { to: '/workflow', label: 'My Tasks' },
  { to: '/customers', label: 'Customers', section: 'Operations' },
  { to: '/products', label: 'Products & Quotes' },
  { to: '/policies', label: 'Policies' },
  { to: '/billing', label: 'Billing', roles: ['FINANCE', 'MANAGER', 'CSR'] },
  { to: '/claims', label: 'Claims' },
  { to: '/lifecycle', label: 'Renewals & Cancellations', roles: ['CSR', 'UNDERWRITER', 'MANAGER', 'FINANCE'] },
  { to: '/reports', label: 'Reports', section: 'Back office' },
  { to: '/reconciliation', label: 'Reconciliation', roles: ['FINANCE', 'MANAGER'] },
  { to: '/notifications', label: 'Notifications', roles: ['MANAGER', 'CSR'] },
  { to: '/batch', label: 'Batch Jobs', roles: [] },
  { to: '/admin', label: 'Users & Audit', roles: [] },
]

export default function Layout() {
  const { user, logout, hasRole } = useAuth()
  return (
    <div className="shell">
      <nav className="sidebar">
        <div className="brand">LegacyIns</div>
        {NAV.filter((n) => !n.roles || hasRole(...n.roles)).map((n) => (
          <div key={n.to}>
            {n.section && <div className="section">{n.section}</div>}
            <NavLink to={n.to} end={n.to === '/'}>{n.label}</NavLink>
          </div>
        ))}
      </nav>
      <main className="main">
        <div className="topbar">
          <div />
          <div className="user">
            <span>{user?.fullName} · <strong>{user?.role}</strong> · {user?.branchCd}</span>
            <button className="secondary" onClick={logout}>Sign out</button>
          </div>
        </div>
        <Outlet />
      </main>
    </div>
  )
}
