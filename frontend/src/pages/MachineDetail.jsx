import { Link, useParams } from 'react-router-dom'
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { usePoll } from '../hooks.js'
import { ago, num, pct, SENSORS, time } from '../format.js'
import { useAuth } from '../auth.jsx'
import { ErrorNote, Panel, RecChip, RiskStrip, Stat, SyncBadge } from '../components/ui.jsx'

function SensorChart({ s, data, baseline }) {
  const last = data.length ? data[data.length - 1][s.key] : null
  return (
    <div className="panel p-3">
      <div className="flex items-baseline justify-between">
        <h3 className="text-base font-bold">{s.label}</h3>
        <span className="font-display text-xl font-bold">{num(last, s.key === 'speed' ? 0 : 2)} <span className="text-xs font-normal text-mute">{s.unit}</span></span>
      </div>
      <div className="h-32">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 6, right: 6, bottom: 0, left: -18 }}>
            <CartesianGrid stroke="#E8DCCB" strokeDasharray="2 4" vertical={false} />
            <XAxis dataKey="t" tick={{ fontSize: 10 }} minTickGap={50} />
            <YAxis tick={{ fontSize: 10 }} domain={['auto', 'auto']} width={44} />
            <Tooltip formatter={(v) => [num(v, 2) + ' ' + s.unit, s.label]} labelStyle={{ fontSize: 12 }} />
            {baseline != null && <ReferenceLine y={baseline} stroke="#3F8A5E" strokeDasharray="4 3" label={{ value: 'baseline', fontSize: 10, fill: '#3F8A5E', position: 'insideTopRight' }} />}
            <Line type="monotone" dataKey={s.key} stroke="#5C7F63" dot={false} strokeWidth={1.8} isAnimationActive={false} connectNulls />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  )
}

const avg = (arr) => (arr.length ? arr.reduce((a, b) => a + b, 0) / arr.length : null)

export default function MachineDetail() {
  const { id } = useParams()
  const { canWrite } = useAuth()
  const machines = usePoll('/api/machines', 0)
  const twin = usePoll(`/api/twins/${id}`, 2000)
  const preds = usePoll(`/api/predictions/${id}?limit=60`, 4000)
  const m = (machines.data || []).find((x) => x.machineId === id)
  const t = twin.data
  const hist = (t?.history || []).map((h) => ({ ...h, t: time(h.ts), risk: h.risk, p: h.failureProbability, a: h.anomalyScore }))
  const ok = t?.aiStatus === 'OK'
  const last = (preds.data || []).slice().reverse()
  const inferAvg = avg(last.map((p) => p.inferenceMs).filter((v) => v != null))
  const e2eAvg = avg(last.map((p) => p.endToEndMs).filter((v) => v != null))
  const hi = t?.healthIndicators || {}

  return (
    <div className="space-y-5">
      <div>
        <Link to="/" className="text-sm font-semibold text-steel underline underline-offset-2">← All machines</Link>
        <div className="mt-1 flex flex-wrap items-end justify-between gap-3">
          <div><h1 className="text-3xl font-bold">{m?.name || id}</h1><p className="text-sm text-mute">{id} {m && `· ${m.type.toLowerCase()} · ${m.location}`}</p></div>
          <SyncBadge status={t?.syncStatus} />
        </div>
      </div>
      <ErrorNote error={twin.error || machines.error} />

      <Panel title="Recommendation (advisory)">
        <div className="flex flex-wrap items-center gap-4"><RecChip rec={ok ? t.recommendation : null} big />
          {t?.lowConfidence && <span className="rounded border border-inspect bg-inspect/10 px-2 py-0.5 text-xs font-semibold text-inspect">Low confidence: human review needed</span>}
          {!ok && t && <span className="text-sm text-mute">{({ WARMING_UP: 'Warming up: the model needs a stable running window.', NOT_RUNNING: 'Machine is not running.', AI_UNAVAILABLE: 'AI service unavailable; showing last known state.', BAD_DATA: 'Latest reading had bad quality.' })[t.aiStatus] || ''}</span>}
        </div>
        <div className="mt-3 max-w-xl"><RiskStrip risk={ok ? t.riskScore : null} height={14} /></div>
        {ok && (
          <ul className="mt-3 list-disc space-y-1 pl-5 text-sm">
            {(t.reasons || []).map((r, i) => <li key={i}>{r}</li>)}
            {t.lowConfidence && (t.lowConfidenceReasons || []).map((r, i) => <li key={'l' + i} className="text-inspect">Review flag: {r}</li>)}
          </ul>
        )}
      </Panel>

      {canWrite && (
      <div className="grid gap-4 lg:grid-cols-3">
        <Panel title="Twin state" className="lg:col-span-2">
          <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Stat label="Composite risk" value={num(t?.riskScore)} sub={`trend ${num(t?.trend)}`} />
            <Stat label="Failure probability" value={pct(t?.failureProbability)} />
            <Stat label="Anomaly score" value={num(t?.anomalyScore)} sub="0 normal · 1 severe" />
            <Stat label="Model" value={t?.modelVersion || '–'} />
            <Stat label="Vibration RMS" value={hi.vibrationRmsRatio != null ? `${num(hi.vibrationRmsRatio)}×` : '–'} sub="vs baseline" />
            <Stat label="Temp. offset" value={hi.temperatureDeltaC != null ? `${hi.temperatureDeltaC > 0 ? '+' : ''}${num(hi.temperatureDeltaC, 1)} °C` : '–'} />
            <Stat label="Crest factor" value={num(hi.crestFactor)} sub="impulsiveness" />
            <Stat label="Data quality" value={t?.dataQuality || '–'} sub={t?.machineState} />
          </dl>
        </Panel>
        <Panel title="Timing">
          <dl className="space-y-3">
            <Stat label="Sensor → twin latency" value={t?.latencyMs != null ? `${num(t.latencyMs, 0)} ms` : '–'} sub={t?.latencyAvgMs != null ? `average ${num(t.latencyAvgMs, 0)} ms` : ''} />
            <Stat label="Model inference" value={inferAvg != null ? `${num(inferAvg, 1)} ms` : '–'} sub="mean of last predictions" />
            <Stat label="Sensor → prediction" value={e2eAvg != null ? `${num(e2eAvg, 0)} ms` : '–'} sub={`last update ${ago(t?.lastUpdate)}`} />
          </dl>
        </Panel>
      </div>
      )}

      <div>
        <h2 className="mb-2 text-xl font-bold">Live sensors <span className="text-sm font-normal text-mute">(last {hist.length} readings, refreshed every 2 s)</span></h2>
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {SENSORS.map((s) => <SensorChart key={s.key} s={s} data={hist} baseline={m?.baselines?.[s.key]?.mean} />)}
          <div className="panel p-3">
            <h3 className="text-base font-bold">Risk, probability, anomaly</h3>
            <div className="h-32">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={hist} margin={{ top: 6, right: 6, bottom: 0, left: -18 }}>
                  <CartesianGrid stroke="#E8DCCB" strokeDasharray="2 4" vertical={false} />
                  <XAxis dataKey="t" tick={{ fontSize: 10 }} minTickGap={50} />
                  <YAxis domain={[0, 1]} tick={{ fontSize: 10 }} width={44} />
                  <Tooltip formatter={(v) => num(v, 2)} labelStyle={{ fontSize: 12 }} />
                  <ReferenceLine y={0.5} stroke="#C46A2B" strokeDasharray="4 3" />
                  <Line dataKey="risk" name="risk" stroke="#3B3430" dot={false} strokeWidth={2} isAnimationActive={false} connectNulls />
                  <Line dataKey="p" name="failure prob." stroke="#C94B45" dot={false} strokeWidth={1.2} isAnimationActive={false} connectNulls />
                  <Line dataKey="a" name="anomaly" stroke="#D9A441" dot={false} strokeWidth={1.2} isAnimationActive={false} connectNulls />
                </LineChart>
              </ResponsiveContainer>
            </div>
            <p className="text-xs text-mute"><span className="font-semibold text-ink">risk</span> · <span className="text-immediate">failure prob.</span> · <span className="text-inspect">anomaly</span></p>
          </div>
        </div>
      </div>

      {canWrite && (
      <Panel title="Prediction log" right={<span className="text-xs text-mute">every prediction is stored with its model version</span>}>
        <div className="overflow-x-auto">
          <table className="w-full"><thead><tr>{['Time', 'Model', 'Recommendation', 'Risk', 'p(fail)', 'Anomaly', 'Inference', 'Sensor→twin'].map((h) => <th key={h} className="th">{h}</th>)}</tr></thead>
            <tbody>{(preds.data || []).slice(-12).reverse().map((p) => (
              <tr key={p.id}><td className="td">{time(p.ts)}</td><td className="td">{p.modelVersion}</td><td className="td"><RecChip rec={p.recommendation} /></td>
                <td className="td">{num(p.risk)}</td><td className="td">{pct(p.failureProbability)}</td><td className="td">{num(p.anomalyScore)}</td>
                <td className="td">{num(p.inferenceMs, 1)} ms</td><td className="td">{num(p.endToEndMs, 0)} ms</td></tr>))}
            </tbody></table>
          {!(preds.data || []).length && <p className="py-3 text-sm text-mute">No predictions yet. The model starts after the warm-up window.</p>}
        </div>
      </Panel>
      )}
    </div>
  )
}
