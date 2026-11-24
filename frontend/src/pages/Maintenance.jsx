import { useState } from 'react'
import { api } from '../api.js'
import { useAuth } from '../auth.jsx'
import { usePoll } from '../hooks.js'
import { dateTime } from '../format.js'
import { ErrorNote, Panel } from '../components/ui.jsx'

const OUTCOMES = [['FAULT_CONFIRMED', 'Fault confirmed'], ['NO_FAULT_FOUND', 'No fault found'], ['PREVENTIVE', 'Preventive work'], ['OTHER', 'Other']]

export default function Maintenance() {
  const { canWrite } = useAuth()
  const machines = usePoll('/api/machines', 0)
  const list = usePoll('/api/maintenance?limit=100', 5000)
  const [f, setF] = useState({ machineId: '', action: '', outcome: 'FAULT_CONFIRMED', notes: '' })
  const [err, setErr] = useState(null)
  const [ok, setOk] = useState(null)
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const submit = async (e) => {
    e.preventDefault(); setErr(null); setOk(null)
    try {
      await api('/api/maintenance', { method: 'POST', body: { ...f, machineId: f.machineId || machines.data?.[0]?.machineId } })
      setOk('Maintenance action recorded. It will be used as feedback by the learning loop.')
      setF({ ...f, action: '', notes: '' }); list.reload()
    } catch (e2) { setErr(e2.message) }
  }
  return (
    <div className="space-y-5">
      <div><h1 className="text-3xl font-bold">Maintenance</h1><p className="text-sm text-mute">Recorded outcomes are the feedback the system learns from.</p></div>
      {canWrite ? (
        <Panel title="Record an action">
          <form onSubmit={submit} className="grid gap-3 md:grid-cols-2">
            <div><label className="label" htmlFor="m">Machine</label>
              <select id="m" className="field" value={f.machineId} onChange={set('machineId')}>{(machines.data || []).map((m) => <option key={m.machineId} value={m.machineId}>{m.machineId} · {m.name}</option>)}</select></div>
            <div><label className="label" htmlFor="o">Outcome</label>
              <select id="o" className="field" value={f.outcome} onChange={set('outcome')}>{OUTCOMES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></div>
            <div className="md:col-span-2"><label className="label" htmlFor="a">Action taken</label><input id="a" className="field" maxLength={200} required value={f.action} onChange={set('action')} placeholder="e.g. Replaced drive-end bearing" /></div>
            <div className="md:col-span-2"><label className="label" htmlFor="n">Notes</label><textarea id="n" rows={3} maxLength={2000} className="field" value={f.notes} onChange={set('notes')} /></div>
            <div className="md:col-span-2 flex items-center gap-3"><button className="btn">Record action</button>{ok && <span className="text-sm font-semibold text-ok">{ok}</span>}</div>
          </form>
          <div className="mt-3"><ErrorNote error={err} /></div>
        </Panel>
      ) : <p className="rounded border border-line bg-panel px-3 py-2 text-sm text-mute">Operators can view maintenance history; engineers and admins record actions.</p>}
      <ErrorNote error={list.error} />
      <Panel title="History">
        <div className="overflow-x-auto"><table className="w-full"><thead><tr>{['When', 'Machine', 'Action', 'Outcome', 'By', 'Notes'].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
          <tbody>{(list.data || []).map((r) => <tr key={r.id}><td className="td whitespace-nowrap">{dateTime(r.performedAt)}</td><td className="td font-semibold">{r.machineId}</td><td className="td">{r.action}</td>
            <td className="td">{(OUTCOMES.find(([v]) => v === r.outcome) || [0, r.outcome])[1]}</td><td className="td">{r.performedBy}</td><td className="td max-w-xs">{r.notes}</td></tr>)}</tbody></table>
          {!(list.data || []).length && <p className="py-3 text-sm text-mute">Nothing recorded yet.</p>}</div>
      </Panel>
    </div>
  )
}
