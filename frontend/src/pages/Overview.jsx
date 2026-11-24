import { Link } from 'react-router-dom'
import { usePoll } from '../hooks.js'
import { ago, num, pct, REC } from '../format.js'
import { ErrorNote, RecChip, RiskStrip, SyncBadge } from '../components/ui.jsx'

function MachineCard({ m, twin }) {
  const t = twin || {}
  const hi = t.healthIndicators || {}
  return (
    <Link to={`/machines/${m.machineId}`} className="panel block p-4 transition hover:border-steel hover:shadow-md">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="text-xl font-bold leading-tight">{m.name}</h3>
          <p className="text-xs text-mute">{m.machineId} · {m.type.toLowerCase()} · {m.location}</p>
        </div>
        <SyncBadge status={t.syncStatus || 'NO_DATA'} />
      </div>
      <div className="mt-3"><RecChip rec={t.aiStatus === 'OK' ? t.recommendation : null} big />
        {t.aiStatus && t.aiStatus !== 'OK' && <p className="mt-1 text-xs text-mute">{({ WARMING_UP: 'Warming up: collecting a stable window', NOT_RUNNING: 'Machine not running', AI_UNAVAILABLE: 'AI service unavailable', BAD_DATA: 'Last reading rejected (bad quality)' })[t.aiStatus]}</p>}
      </div>
      <div className="mt-2"><RiskStrip risk={t.aiStatus === 'OK' ? t.riskScore : null} /></div>
      <dl className="mt-3 grid grid-cols-3 gap-2 text-sm">
        <div><dt className="text-xs text-mute">Risk</dt><dd className="font-semibold">{num(t.riskScore)}</dd></div>
        <div><dt className="text-xs text-mute">Failure prob.</dt><dd className="font-semibold">{pct(t.failureProbability)}</dd></div>
        <div><dt className="text-xs text-mute">Vibration</dt><dd className="font-semibold">{hi.vibrationRmsRatio ? `${num(hi.vibrationRmsRatio)}× base` : '–'}</dd></div>
      </dl>
      <p className="mt-3 text-xs text-mute">
        {t.lowConfidence && <span className="mr-2 font-semibold text-inspect">Needs human review ·</span>}
        updated {ago(t.lastUpdate)}{t.latencyAvgMs != null && ` · latency ${num(t.latencyAvgMs, 0)} ms`}
      </p>
    </Link>
  )
}

export default function Overview() {
  const machines = usePoll('/api/machines', 0)
  const twins = usePoll('/api/twins', 2000)
  const byId = Object.fromEntries((twins.data || []).map((t) => [t.machineId, t]))
  const worst = Math.max(-1, ...(twins.data || []).filter((t) => t.aiStatus === 'OK').map((t) => REC[t.recommendation]?.rank ?? 0))
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-3xl font-bold">Machine overview</h1>
        <p className="text-sm text-mute">{worst >= 2 ? 'At least one machine needs maintenance attention.' : worst >= 0 ? 'No machine currently needs maintenance.' : 'Waiting for the first assessments.'} Recommendations are advisory; people decide.</p>
      </div>
      <ErrorNote error={machines.error || twins.error} />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {(machines.data || []).map((m) => <MachineCard key={m.machineId} m={m} twin={byId[m.machineId]} />)}
      </div>
    </div>
  )
}
