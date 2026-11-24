// Thin fetch wrapper: adds the JWT, turns errors into Error(message), signals 401 to the auth layer.
let token = null
let onUnauthorized = () => {}
export const setToken = (t) => { token = t }
export const setUnauthorizedHandler = (fn) => { onUnauthorized = fn }

// Empty in Docker/dev (same-origin /api via nginx or the Vite proxy). Set VITE_API_URL at build time when the API is on another host (e.g. Render).
const BASE = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

export async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (res.status === 401 && token) { onUnauthorized(); throw new Error('Session expired, please sign in again') }
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    const detail = data?.details ? ' (' + Object.entries(data.details).map(([k, v]) => `${k}: ${v}`).join(', ') + ')' : ''
    throw new Error((data?.error || `Request failed (${res.status})`) + detail)
  }
  return data
}
