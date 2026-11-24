import { usePoll } from '../hooks.js'
import { dateTime } from '../format.js'
import { ErrorNote, Panel } from '../components/ui.jsx'

export default function Audit() {
  const { data, error } = usePoll('/api/audit?limit=200', 5000)
  return (
    <div className="space-y-5">
      <div><h1 className="text-3xl font-bold">Audit log</h1>
        <p className="text-sm text-mute">Logins, maintenance records, alert acknowledgements, simulator actions and model approvals (newest first).</p></div>
      <ErrorNote error={error} />
      <Panel>
        <div className="overflow-x-auto">
          <table className="w-full"><thead><tr>{['When', 'User', 'Action', 'Target', 'Details'].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
            <tbody>{(data || []).map((a) => (
              <tr key={a.id}><td className="td whitespace-nowrap">{dateTime(a.ts)}</td><td className="td font-semibold">{a.user}</td><td className="td">{a.action}</td>
                <td className="td">{a.target}</td><td className="td max-w-md">{a.details}</td></tr>))}
            </tbody></table>
          {!(data || []).length && <p className="py-3 text-sm text-mute">No entries yet.</p>}
        </div>
      </Panel>
    </div>
  )
}
