export type Row = Record<string, unknown>

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface User {
  id: number
  username: string
  fullName: string
  email: string
  role: string
  branchCd: string
}

export class ApiError extends Error {
  constructor(public code: string, message: string, public details: string[] = []) {
    super(message)
  }
}

const TOKEN_KEY = 'ins.token'
const USER_KEY = 'ins.user'

export const session = {
  token: () => localStorage.getItem(TOKEN_KEY),
  user: (): User | null => {
    const raw = localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as User) : null
  },
  save: (token: string, user: User) => {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  },
  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },
}

let onUnauthorized: () => void = () => {}
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  const token = session.token()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`/api${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await res.text()
  const data = text ? (res.headers.get('content-type')?.includes('json') ? JSON.parse(text) : text) : null
  if (!res.ok) {
    const err = (data ?? {}) as { code?: string; message?: string; details?: string[] }
    if (res.status === 401 && path !== '/auth/login') onUnauthorized()
    throw new ApiError(err.code ?? 'SYSTEM', err.message ?? res.statusText, err.details ?? [])
  }
  return data as T
}

export const api = {
  get: <T = Row>(path: string) => request<T>('GET', path),
  post: <T = Row>(path: string, body: unknown = {}) => request<T>('POST', path, body),
  put: <T = Row>(path: string, body: unknown) => request<T>('PUT', path, body),
  patch: <T = Row>(path: string, body: unknown) => request<T>('PATCH', path, body),
  del: <T = Row>(path: string) => request<T>('DELETE', path),
  download: async (path: string, filename: string) => {
    const res = await fetch(`/api${path}`, { headers: { Authorization: `Bearer ${session.token()}` } })
    if (!res.ok) throw new ApiError('SYSTEM', `Download failed (${res.status})`)
    const url = URL.createObjectURL(await res.blob())
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  },
}

export const qs = (params: Record<string, string | number | undefined | null>) => {
  const p = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') p.set(k, String(v))
  })
  const s = p.toString()
  return s ? `?${s}` : ''
}
