import { useState } from 'react'
import { useAuth } from '../auth.jsx'
import { BANDS, REC } from '../format.js'

export default function Login() {
  const { login } = useAuth()
  const [username, setU] = useState('')
  const [password, setP] = useState('')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const submit = async (e) => {
    e.preventDefault()
    setBusy(true); setError(null)
    try { await login(username.trim(), password) } catch (err) { setError(err.message) } finally { setBusy(false) }
  }
  return (
    <div className="grid min-h-screen place-items-center bg-gradient-to-b from-paper to-side p-6">
      <div className="w-full max-w-sm">
        <h1 className="font-display text-5xl font-bold text-ink">IntelliTwin</h1>
        <p className="mt-1 mb-5 text-sm text-mute">A self-learning digital twin for maintenance decisions.</p>
        <div className="mb-6 flex h-2 overflow-hidden rounded-full" aria-hidden="true">
          {BANDS.map((b) => <div key={b.rec} className={REC[b.rec].cls} style={{ width: `${(b.to - b.from) * 100}%`, marginRight: 1 }} />)}
        </div>
        <form onSubmit={submit} className="space-y-4 rounded-2xl border border-line bg-panel p-6 shadow-md">
          <div><label className="label" htmlFor="u">Username</label><input id="u" className="field" autoComplete="username" value={username} onChange={(e) => setU(e.target.value)} required /></div>
          <div><label className="label" htmlFor="p">Password</label><input id="p" type="password" className="field" autoComplete="current-password" value={password} onChange={(e) => setP(e.target.value)} required /></div>
          {error && <p role="alert" className="text-sm font-semibold text-immediate">{error}</p>}
          <button className="btn w-full" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
      </div>
    </div>
  )
}
