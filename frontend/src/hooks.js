import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'

/** Polls `path` every `ms`; keeps the last good data when a poll fails. */
export function usePoll(path, ms = 2000) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const alive = useRef(true)
  const load = useCallback(async () => {
    if (!path) return
    try { const d = await api(path); if (alive.current) { setData(d); setError(null) } }
    catch (e) { if (alive.current) setError(e.message) }
    finally { if (alive.current) setLoading(false) }
  }, [path])
  useEffect(() => {
    alive.current = true
    setLoading(true)
    load()
    const id = ms ? setInterval(load, ms) : null
    return () => { alive.current = false; if (id) clearInterval(id) }
  }, [load, ms])
  return { data, error, loading, reload: load }
}
