import { useState } from 'react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { usePoll } from '../hooks.js'
import { dateTime } from '../format.js'
import { ErrorNote, Panel } from '../components/ui.jsx'

const ROLES = [['OPERATOR', 'Operator: view and acknowledge'], ['ENGINEER', 'Engineer: + maintenance, simulator, models, audit'], ['ADMIN', 'Admin: everything + approvals + users']]

export default function Users() {
  const { username: me } = useAuth()
  const { data, error, reload } = usePoll('/api/users', 0)
  const [f, setF] = useState({ username: '', password: '', role: 'OPERATOR' })
  const [err, setErr] = useState(null)
  const [ok, setOk] = useState(null)
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const create = async (e) => {
    e.preventDefault(); setErr(null); setOk(null)
    try { await api('/api/users', { method: 'POST', body: f }); setOk(`User ${f.username} created.`); setF({ username: '', password: '', role: 'OPERATOR' }); reload() } catch (e2) { setErr(e2.message) }
  }
  const remove = async (u) => {
    if (!window.confirm(`Delete user ${u}?`)) return
    setErr(null); setOk(null)
    try { await api(`/api/users/${encodeURIComponent(u)}`, { method: 'DELETE' }); reload() } catch (e2) { setErr(e2.message) }
  }
  return (
    <div className="space-y-5">
      <div><h1 className="text-3xl font-bold">Users</h1><p className="text-sm text-mute">Administrators create and remove accounts and choose what each person may do.</p></div>
      <ErrorNote error={error || err} />
      <Panel title="Add a user">
        <form onSubmit={create} className="grid gap-3 md:grid-cols-3">
          <div><label className="label" htmlFor="un">Username</label><input id="un" className="field" required minLength={3} maxLength={32} pattern="[A-Za-z0-9_.\-]{3,32}" value={f.username} onChange={set('username')} autoComplete="off" /></div>
          <div><label className="label" htmlFor="pw">Password (min 8)</label><input id="pw" type="password" className="field" required minLength={8} maxLength={128} value={f.password} onChange={set('password')} autoComplete="new-password" /></div>
          <div><label className="label" htmlFor="ro">Role</label><select id="ro" className="field" value={f.role} onChange={set('role')}>{ROLES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
          <div className="md:col-span-3 flex items-center gap-3"><button className="btn">Create user</button>{ok && <span className="text-sm font-semibold text-ok">{ok}</span>}</div>
        </form>
      </Panel>
      <Panel title="Accounts">
        <div className="overflow-x-auto"><table className="w-full"><thead><tr>{['Username', 'Role', 'Created', ''].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
          <tbody>{(data || []).map((u) => (
            <tr key={u.username}><td className="td font-semibold">{u.username}{u.username === me && <span className="ml-2 text-xs text-mute">(you)</span>}</td><td className="td">{u.role}</td><td className="td">{dateTime(u.createdAt)}</td>
              <td className="td">{u.username !== me && <button className="btn-ghost" onClick={() => remove(u.username)}>Delete</button>}</td></tr>))}
          </tbody></table></div>
      </Panel>
    </div>
  )
}
