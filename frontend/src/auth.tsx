import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, session, setUnauthorizedHandler, type User } from './api/client'

interface AuthState {
  user: User | null
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  hasRole: (...roles: string[]) => boolean
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(session.user())

  const logout = () => {
    session.clear()
    setUser(null)
  }

  useEffect(() => setUnauthorizedHandler(logout), [])

  const login = async (username: string, password: string) => {
    const r = await api.post<{ token: string; user: User }>('/auth/login', { username, password })
    session.save(r.token, r.user)
    setUser(r.user)
  }

  const hasRole = (...roles: string[]) => !!user && (user.role === 'ADMIN' || roles.includes(user.role))

  return <AuthContext.Provider value={{ user, login, logout, hasRole }}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
