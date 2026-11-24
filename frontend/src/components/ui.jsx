import { BANDS, REC, num } from '../format.js'

export function RecChip({ rec, big = false }) {
  const r = REC[rec]
  if (!r) return <span className="text-xs text-mute">no assessment yet</span>
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border border-line bg-white ${big ? 'px-3 py-1 text-base' : 'px-2 py-0.5 text-xs'} font-semibold`}>
      <span className={`inline-block rounded-full ${r.cls} ${big ? 'h-3 w-3' : 'h-2 w-2'}`} aria-hidden="true" />
      {r.label}
    </span>
  )
}

/** Segmented 0..1 risk scale with a marker at the current composite risk. The signature element of the dashboard. */
export function RiskStrip({ risk, height = 10 }) {
  const known = risk !== null && risk !== undefined
  return (
    <div className="relative pt-3" role="img" aria-label={known ? `risk ${num(risk)} of 1` : 'risk not yet evaluated'}>
      <div className="flex w-full overflow-hidden rounded-sm" style={{ height }}>
        {BANDS.map((b) => (
          <div key={b.rec} className={`${REC[b.rec].cls} ${known ? 'opacity-40' : 'opacity-20'}`} style={{ width: `${(b.to - b.from) * 100}%`, marginRight: 1 }} />
        ))}
      </div>
      {known && (
        <div className="absolute top-0 -translate-x-1/2 transition-[left] duration-700" style={{ left: `${Math.min(Math.max(risk, 0), 1) * 100}%` }}>
          <div className="mx-auto h-0 w-0 border-x-[5px] border-t-[8px] border-x-transparent border-t-ink" />
        </div>
      )}
    </div>
  )
}

export function Panel({ title, right, children, className = '' }) {
  return (
    <section className={`panel ${className}`}>
      {(title || right) && (
        <header className="flex items-center justify-between border-b border-line px-4 py-2.5">
          <h2 className="text-lg font-bold">{title}</h2>
          <div>{right}</div>
        </header>
      )}
      <div className="p-4">{children}</div>
    </section>
  )
}

export function SyncBadge({ status }) {
  const map = { SYNCED: ['bg-ok', 'Synced'], STALE: ['bg-stale', 'Stale'], NO_DATA: ['bg-mute', 'No data'] }
  const [cls, label] = map[status] || ['bg-mute', status || '–']
  return <span className="inline-flex items-center gap-1.5 text-xs font-semibold"><span className={`h-2 w-2 rounded-full ${cls}`} aria-hidden="true" />{label}</span>
}

export function ErrorNote({ error }) {
  return error ? <p role="alert" className="rounded border border-immediate/40 bg-immediate/10 px-3 py-2 text-sm text-immediate">{error}</p> : null
}

export function Stat({ label, value, sub }) {
  return (
    <div>
      <dt className="text-xs font-semibold text-mute">{label}</dt>
      <dd className="font-display text-2xl font-bold leading-tight">{value}</dd>
      {sub && <dd className="text-xs text-mute">{sub}</dd>}
    </div>
  )
}
