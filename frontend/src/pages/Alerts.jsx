import { useState } from 'react'
import { api } from '../api.js'
import { usePoll } from '../hooks.js'
import { dateTime } from '../format.js'
import { ErrorNote, Panel, RecChip } from '../components/ui.jsx'

export default function Alerts() {
  const [status, setStatus] = useState('OPEN')
  const [err, setErr] = useState(null)
  const { data, error, reload } = usePoll(`/api/alerts?limit=200${status ? `&status=${status}` : ''}`, 3000)
  const ack = async (id) => {
    setErr(null)
    try { await api(`/api/alerts/${id}`, { method: 'PATCH', body: { status: 'ACKNOWLEDGED' } }); reload() } catch (e) { setErr(e.message) }
  }
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div><h1 className="text-3xl font-bold">Alerts</h1><p className="text-sm text-mute">Raised when a recommendation escalates or a twin goes stale.</p></div>
        <div><label className="label" htmlFor="f">Show</label>
          <select id="f" className="field" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="OPEN">Open</option><option value="ACKNOWLEDGED">Acknowledged</option><option value="">All</option></select></div>
      </div>
      <ErrorNote error={error || err} />
      <Panel>
        <div className="overflow-x-auto">
          <table className="w-full"><thead><tr>{['Raised', 'Machine', 'Type', 'Level', 'Detail', 'Status', ''].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
            <tbody>{(data || []).map((a) => (
              <tr key={a.id}>
                <td className="td whitespace-nowrap">{dateTime(a.createdAt)}</td><td className="td font-semibold">{a.machineId}</td>
                <td className="td">{a.type === 'STALE' ? <span className="font-semibold text-stale">Stale twin</span> : 'Risk'}</td>
                <td className="td">{a.type === 'RISK' ? <RecChip rec={a.recommendation} /> : '–'}</td>
                <td className="td max-w-md">{a.message}</td>
                <td className="td">{a.status === 'OPEN' ? <span className="font-semibold">Open</span> : <span className="text-mute">Ack by {a.ackBy}</span>}</td>
                <td className="td">{a.status === 'OPEN' && <button className="btn-ghost" onClick={() => ack(a.id)}>Acknowledge</button>}</td>
              </tr>))}
            </tbody></table>
          {!(data || []).length && <p className="py-3 text-sm text-mute">No alerts in this view.</p>}
        </div>
      </Panel>
    </div>
  )
}
