import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, setToken, setUnauthorizedHandler } from './api.js'

const Ctx = createContext(null)
export const useAuth = () => useContext(Ctx)

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem('it_session')) } catch { return null }
  })
  useEffect(() => { setToken(session?.token ?? null) }, [session])
  const logout = useCallback(() => { sessionStorage.removeItem('it_session'); setToken(null); setSession(null) }, [])
  useEffect(() => { setUnauthorizedHandler(logout) }, [logout])

  const login = useCallback(async (username, password) => {
    const r = await api('/api/auth/login', { method: 'POST', body: { username, password } })
    setToken(r.token)
    sessionStorage.setItem('it_session', JSON.stringify(r))
    setSession(r)
  }, [])

  const value = useMemo(() => ({
    session, login, logout,
    role: session?.role, username: session?.username,
    isAdmin: session?.role === 'ADMIN',
    isOperator: session?.role === 'OPERATOR',
    canWrite: session?.role === 'ADMIN' || session?.role === 'ENGINEER',
  }), [session, login, logout])
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
