import { useState } from 'react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { usePoll } from '../hooks.js'
import { dateTime, num } from '../format.js'
import { ErrorNote, Panel } from '../components/ui.jsx'

const STATUS_CLS = { ACTIVE: 'bg-ok text-white', SHADOW: 'bg-steel text-white', CANDIDATE: 'bg-inspect text-white', ARCHIVED: 'bg-line text-ink', ROLLED_BACK: 'bg-immediate text-white' }

function MetricRow({ label, m }) {
  if (!m) return null
  return <tr><td className="td font-semibold">{label}</td><td className="td">{num(m.precision, 3)}</td><td className="td">{num(m.recall, 3)}</td><td className="td">{num(m.f1, 3)}</td><td className="td">{num(m.roc_auc, 3)}</td></tr>
}

function Metrics({ m }) {
  const test = m.metrics?.test
  const hold = m.metrics?.recent_holdout
  const rows = test ? [['Random forest', test.random_forest], ['Isolation forest', test.isolation_forest], ['Threshold baseline', test.threshold_baseline]]
    : hold ? [[`Candidate ${m.version}`, hold.candidate?.random_forest], [`Active ${hold.active_version}`, hold.active?.random_forest], ['Threshold baseline', hold.candidate?.threshold_baseline]] : []
  if (!rows.length) return <p className="text-sm text-mute">No metrics recorded.</p>
  return (
    <div>
      <p className="mb-1 text-xs text-mute">{test ? `Held-out test set, ${test.n_samples} windows, simulated data` : `Chronological holdout of recent data, ${hold.candidate?.n_samples} windows`}</p>
      <table className="w-full"><thead><tr>{['Model', 'Precision', 'Recall', 'F1', 'ROC-AUC'].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
        <tbody>{rows.map(([l, v]) => <MetricRow key={l} label={l} m={v} />)}</tbody></table>
    </div>
  )
}

export default function Models() {
  const { isAdmin } = useAuth()
  const { data, error, reload } = usePoll('/api/models', 6000)
  const [err, setErr] = useState(null)
  const [busy, setBusy] = useState(false)
  const act = async (fn) => { setErr(null); setBusy(true); try { await fn(); await reload() } catch (e) { setErr(e.message) } finally { setBusy(false) } }
  const promote = (m) => { if (window.confirm(`Promote model ${m.version} to ACTIVE? It will immediately drive all recommendations.`)) act(() => api(`/api/models/${m.version}/promote`, { method: 'POST', body: { note: 'approved via dashboard' } })) }
  const rollback = () => { if (window.confirm('Roll back to the previous model version?')) act(() => api('/api/models/rollback', { method: 'POST' })) }
  const hasPrev = (data || []).some((m) => m.status === 'ACTIVE' && m.parent) || (data || []).some((m) => m.status === 'ARCHIVED')

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div><h1 className="text-3xl font-bold">Models</h1><p className="text-sm text-mute">Candidates run in shadow mode and become active only after an admin approves them.</p></div>
        {isAdmin && <button className="btn-ghost" onClick={rollback} disabled={busy || !hasPrev}>Roll back to previous</button>}
      </div>
      <ErrorNote error={error || err} />
      {!isAdmin && <p className="rounded border border-line bg-panel px-3 py-2 text-sm text-mute">Promotion and rollback are restricted to administrators.</p>}
      {(data || []).map((m) => {
        const ev = m.shadowEvaluation
        const dr = m.driftReport
        return (
          <Panel key={m.version} title={`${m.version}`} right={<span className={`rounded px-2 py-0.5 text-xs font-bold ${STATUS_CLS[m.status] || 'bg-line'}`}>{m.status}</span>}>
            <div className="grid gap-5 lg:grid-cols-2">
              <div className="space-y-1 text-sm">
                <p><span className="text-mute">Created</span> {dateTime(m.createdAt)} {m.parent && <span className="text-mute">· derived from {m.parent}</span>}</p>
                <p><span className="text-mute">Kind</span> {m.trainingDataSummary?.kind || '–'} {m.trainingDataSummary?.train_summary && <span className="text-mute">· {m.trainingDataSummary.train_summary.rows} training windows</span>}</p>
                {m.approvedBy && <p><span className="text-mute">Approved by</span> {m.approvedBy} {m.promotedAt && <span className="text-mute">· {dateTime(m.promotedAt)}</span>}</p>}
                {dr && <p><span className="text-mute">Drift check</span> {dr.drift_detected ? <b className="text-schedule">drift detected ({dr.n_drifted} features)</b> : 'no drift'}{dr.reason && <span className="text-mute"> · {dr.reason}</span>}</p>}
                {ev && <p><span className="text-mute">Shadow</span> {ev.n_scored} scored, {ev.n_labelled} labelled · {ev.promotion_ready ? <b className="text-ok">candidate matches or beats active</b> : <span>{ev.reason}</span>}</p>}
                {isAdmin && ['CANDIDATE', 'SHADOW', 'ARCHIVED'].includes(m.status) && <div className="pt-2"><button className="btn" disabled={busy} onClick={() => promote(m)}>Approve & promote</button></div>}
              </div>
              <Metrics m={m} />
            </div>
          </Panel>
        )
      })}
      {data && !data.length && <p className="text-sm text-mute">No models yet. Run the training script.</p>}
    </div>
  )
}
