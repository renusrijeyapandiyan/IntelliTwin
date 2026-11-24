export const REC = {
  CONTINUE_MONITORING: { label: 'Continue monitoring', cls: 'bg-ok', text: 'text-ok', rank: 0 },
  INSPECT_MACHINE: { label: 'Inspect machine', cls: 'bg-inspect', text: 'text-inspect', rank: 1 },
  SCHEDULE_MAINTENANCE: { label: 'Schedule maintenance', cls: 'bg-schedule', text: 'text-schedule', rank: 2 },
  IMMEDIATE_INTERVENTION: { label: 'Immediate intervention', cls: 'bg-immediate', text: 'text-immediate', rank: 3 },
}
// Display copy of the decision thresholds (the authoritative values live in backend application.yml)
export const BANDS = [
  { from: 0, to: 0.30, rec: 'CONTINUE_MONITORING' },
  { from: 0.30, to: 0.50, rec: 'INSPECT_MACHINE' },
  { from: 0.50, to: 0.75, rec: 'SCHEDULE_MAINTENANCE' },
  { from: 0.75, to: 1.0, rec: 'IMMEDIATE_INTERVENTION' },
]
export const SENSORS = [
  { key: 'temperature', label: 'Temperature', unit: '°C' },
  { key: 'vibration', label: 'Vibration', unit: 'mm/s' },
  { key: 'pressure', label: 'Pressure', unit: 'bar' },
  { key: 'current', label: 'Current', unit: 'A' },
  { key: 'speed', label: 'Speed', unit: 'rpm' },
]
export const num = (v, d = 2) => (v === null || v === undefined || Number.isNaN(v) ? '–' : Number(v).toFixed(d))
export const pct = (v) => (v === null || v === undefined ? '–' : `${Math.round(v * 100)}%`)
export const time = (t) => (t ? new Date(t).toLocaleTimeString() : '–')
export const dateTime = (t) => (t ? new Date(t).toLocaleString() : '–')
export const ago = (t) => {
  if (!t) return '–'
  const s = Math.max(0, Math.round((Date.now() - new Date(t).getTime()) / 1000))
  return s < 60 ? `${s} s ago` : s < 3600 ? `${Math.round(s / 60)} min ago` : `${Math.round(s / 3600)} h ago`
}
