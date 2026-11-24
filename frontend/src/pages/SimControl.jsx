import { useState } from 'react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { usePoll } from '../hooks.js'
import { ErrorNote, Panel } from '../components/ui.jsx'

const FAULTS = [['bearing_wear', 'Bearing wear (gradual)'], ['jam', 'Jam (sudden)'], ['leakage', 'Leakage'], ['imbalance', 'Imbalance'], ['lube_loss', 'Lubrication loss']]

export default function SimControl() {
  const { canWrite } = useAuth()
  const { data, error, reload } = usePoll('/api/sim/status', 2000)
  const [machineId, setM] = useState('M-001')
  const [fault, setF] = useState('bearing_wear')
  const [ramp, setRamp] = useState(150)
  const [sev, setSev] = useState(0.4)
  const [err, setErr] = useState(null)
  const run = async (fn) => { setErr(null); try { await fn(); await reload() } catch (e) { setErr(e.message) } }
  const machines = Object.keys(data?.machines || {})
  return (
    <div className="space-y-5">
      <div><h1 className="text-3xl font-bold">Simulator control</h1>
        <p className="text-sm text-mute">Demo only. Faults are injected into simulated sensor data; no real machine is ever controlled.</p></div>
      <ErrorNote error={error || err} />
      {canWrite ? (
        <Panel title="Inject a fault">
          <div className="grid gap-3 md:grid-cols-4">
            <div><label className="label" htmlFor="sm">Machine</label><select id="sm" className="field" value={machineId} onChange={(e) => setM(e.target.value)}>{(machines.length ? machines : ['M-001', 'M-002', 'M-003']).map((m) => <option key={m}>{m}</option>)}</select></div>
            <div><label className="label" htmlFor="sf">Fault</label><select id="sf" className="field" value={fault} onChange={(e) => setF(e.target.value)}>{FAULTS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
            <div><label className="label" htmlFor="sr">Ramp-up (seconds)</label><input id="sr" type="number" min="1" max="7200" className="field" value={ramp} onChange={(e) => setRamp(+e.target.value)} /></div>
            <div><label className="label" htmlFor="ss">Final severity (0 to 1)</label><input id="ss" type="number" min="0" max="1" step="0.05" className="field" value={sev} onChange={(e) => setSev(+e.target.value)} /></div>
          </div>
          <p className="mt-2 text-xs text-mute">Severity 0.4 gives a moderate fault; 1.0 a severe one. Higher severity escalates the recommendation further.</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <button className="btn" onClick={() => run(() => api('/api/sim/faults', { method: 'POST', body: { machineId, fault, rampSeconds: ramp, severity: sev } }))}>Inject fault</button>
            <button className="btn-ghost" onClick={() => run(() => api(`/api/sim/faults/${machineId}`, { method: 'DELETE' }))}>Clear faults on {machineId}</button>
            <button className="btn-ghost" onClick={() => run(() => api(`/api/sim/machines/${machineId}/stop`, { method: 'POST' }))}>Stop machine</button>
            <button className="btn-ghost" onClick={() => run(() => api(`/api/sim/machines/${machineId}/start`, { method: 'POST' }))}>Start machine</button>
          </div>
        </Panel>
      ) : <p className="rounded border border-line bg-panel px-3 py-2 text-sm text-mute">Fault injection is limited to engineers and admins.</p>}
      <Panel title="Simulator state">
        <table className="w-full"><thead><tr>{['Machine', 'Mode', 'Load', 'Readings sent', 'Active faults (severity now)'].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
          <tbody>{Object.entries(data?.machines || {}).map(([id, s]) => (
            <tr key={id}><td className="td font-semibold">{id}</td><td className="td">{s.mode}</td><td className="td">{Math.round(s.load * 100)}%</td><td className="td">{s.seq}</td>
              <td className="td">{Object.keys(s.faults).length ? Object.entries(s.faults).map(([k, v]) => `${k} (${v.toFixed(2)})`).join(', ') : <span className="text-mute">none</span>}</td></tr>))}</tbody></table>
      </Panel>
    </div>
  )
}
