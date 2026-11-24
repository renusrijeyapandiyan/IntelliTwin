import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth.jsx'
import { usePoll } from '../hooks.js'

// [path, label, roles allowed to see it]
const ALL = ['ADMIN', 'ENGINEER', 'OPERATOR']
const STAFF = ['ADMIN', 'ENGINEER']
const links = [
  ['/', 'Overview', ALL], ['/alerts', 'Alerts', ALL], ['/maintenance', 'Maintenance', ALL],
  ['/models', 'Models', STAFF], ['/simulator', 'Simulator', STAFF], ['/audit', 'Audit log', STAFF], ['/users', 'Users', ['ADMIN']],
]
const ABOUT = {
  OPERATOR: 'Watch machines and acknowledge alerts.',
  ENGINEER: 'Record maintenance, inject test faults, review models and the audit log.',
  ADMIN: 'Everything, plus approving models and managing users.',
}

function StaleBanner() {
  const { data } = usePoll('/api/twins', 2000)
  const stale = (data || []).filter((t) => t.syncStatus === 'STALE')
  if (!stale.length) return null
  return (
    <div role="alert" className="border-b border-stale bg-stale px-6 py-2 text-sm font-semibold text-white">
      Stale digital twin{stale.length > 1 ? 's' : ''}: {stale.map((t) => t.machineId).join(', ')}. No recent sensor data, so the values shown may be out of date and recommendations should not be relied on.
    </div>
  )
}

export default function Layout() {
  const { username, role, logout } = useAuth()
  const nav = links.filter(([, , roles]) => roles.includes(role))
  return (
    <div className="flex min-h-screen">
      <nav className="flex w-56 shrink-0 flex-col border-r border-line bg-side text-ink" aria-label="Main">
        <div className="px-5 py-5">
          <div className="font-display text-2xl font-bold leading-none">IntelliTwin</div>
          <div className="mt-1 text-xs text-mute">Advisory decision support. Never controls machines.</div>
        </div>
        <ul className="flex-1 space-y-0.5 px-2">
          {nav.map(([to, label]) => (
            <li key={to}>
              <NavLink to={to} end={to === '/'} className={({ isActive }) => `block rounded-lg px-3 py-2 text-sm font-semibold ${isActive ? 'bg-white text-steelDark shadow-sm' : 'text-ink/80 hover:bg-white/60'}`}>{label}</NavLink>
            </li>
          ))}
        </ul>
        <div className="border-t border-line px-5 py-4 text-sm">
          <div className="font-semibold">{username}</div>
          <div className="text-xs font-semibold uppercase tracking-wide text-steelDark">{role}</div>
          <div className="mt-1 text-xs text-mute">{ABOUT[role]}</div>
          <button onClick={logout} className="mt-2 text-xs underline underline-offset-2 hover:text-steelDark">Sign out</button>
        </div>
      </nav>
      <div className="min-w-0 flex-1">
        <StaleBanner />
        <main className="mx-auto max-w-6xl p-6"><Outlet /></main>
      </div>
    </div>
  )
}
