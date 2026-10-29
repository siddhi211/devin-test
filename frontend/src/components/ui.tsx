import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError, type Row } from '../api/client'

export const fmt = (v: unknown): string => {
  if (v === null || v === undefined || v === '') return '—'
  if (typeof v === 'boolean') return v ? 'Yes' : 'No'
  if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toLocaleString(undefined, { maximumFractionDigits: 2 })
  if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(v)) return v.replace('T', ' ').slice(0, 16)
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

export const money = (v: unknown) =>
  v === null || v === undefined ? '—' : Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export const errorText = (e: unknown) =>
  e instanceof ApiError ? `${e.code}: ${e.message}${e.details.length ? ' — ' + e.details.join('; ') : ''}` : String(e)

/** Loads data from an async function and exposes reload/error state. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(fn, deps)
  const reload = useCallback(() => {
    setLoading(true)
    load()
      .then((d) => {
        setData(d)
        setError(null)
      })
      .catch((e) => setError(errorText(e)))
      .finally(() => setLoading(false))
  }, [load])
  useEffect(reload, [reload])
  return { data, error, loading, reload }
}

/** Wraps a mutating action with busy/error/success feedback. */
export function useAction() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const run = async <T,>(fn: () => Promise<T>, success?: string | ((r: T) => string)): Promise<T | undefined> => {
    setBusy(true)
    setError(null)
    setMessage(null)
    try {
      const r = await fn()
      if (success) setMessage(typeof success === 'function' ? success(r) : success)
      return r
    } catch (e) {
      setError(errorText(e))
      return undefined
    } finally {
      setBusy(false)
    }
  }
  return { busy, error, message, run, clear: () => { setError(null); setMessage(null) } }
}

export function Alert({ error, message }: { error?: string | null; message?: string | null }) {
  if (error) return <div className="alert error">{error}</div>
  if (message) return <div className="alert ok">{message}</div>
  return null
}

export function Card({ title, actions, children }: { title?: ReactNode; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-head">
          <h3>{title}</h3>
          <div className="actions">{actions}</div>
        </header>
      )}
      {children}
    </section>
  )
}

export function Badge({ value }: { value: unknown }) {
  const v = String(value ?? '')
  const tone =
    /ACTIVE|APPROVED|POSTED|PAID|SETTLED|SUCCESS|VERIFIED|SENT|COMPLETED|ACCEPTED|RESOLVED|^A$|^Y$/.test(v) ? 'green'
      : /DECLINED|REJECTED|FAILED|CANCELLED|LAPSED|DEAD|BREACH|ERROR|EXPIRED|REVERSED|^N$/.test(v) ? 'red'
        : /PENDING|GRACE|OPEN|REFERRED|OVERDUE|CLAIMED|SUBMITTED|RUNNING|PARTIAL/.test(v) ? 'amber' : 'grey'
  return <span className={`badge ${tone}`}>{v || '—'}</span>
}

export interface Column<T> {
  key: string
  label?: string
  render?: (row: T) => ReactNode
}

const STATUS_KEYS = /status|state|outcome|decision|result/i
const MONEY_KEYS = /amount|premium|paid|due|balance|debit|credit|total|fee|outstanding|refund|reserve|sum_insured|suminsured/i

export function DataTable<T extends object = Row>({ rows, columns, onRowClick, empty = 'No records' }: {
  rows: T[] | null | undefined
  columns?: Column<T>[]
  onRowClick?: (row: T) => void
  empty?: string
}) {
  if (!rows || rows.length === 0) return <p className="muted">{empty}</p>
  const cols: Column<T>[] = columns ?? Object.keys(rows[0]).slice(0, 10).map((k) => ({ key: k }))
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>{cols.map((c) => <th key={c.key}>{c.label ?? c.key.replace(/_/g, ' ')}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} onClick={onRowClick ? () => onRowClick(r) : undefined} className={onRowClick ? 'clickable' : ''}>
              {cols.map((c) => {
                const v = (r as Record<string, unknown>)[c.key]
                return (
                  <td key={c.key}>
                    {c.render ? c.render(r) : STATUS_KEYS.test(c.key) && typeof v === 'string' ? <Badge value={v} />
                      : MONEY_KEYS.test(c.key) && (typeof v === 'number' || (typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v)))
                        ? money(v) : fmt(v)}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function KeyValues({ data, keys }: { data: Row | null | undefined; keys?: string[] }) {
  if (!data) return null
  const ks = keys ?? Object.keys(data).filter((k) => typeof data[k] !== 'object' || data[k] === null)
  return (
    <dl className="kv">
      {ks.map((k) => (
        <div key={k}>
          <dt>{k.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2')}</dt>
          <dd>{STATUS_KEYS.test(k) ? <Badge value={data[k]} /> : MONEY_KEYS.test(k) ? money(data[k]) : fmt(data[k])}</dd>
        </div>
      ))}
    </dl>
  )
}

export interface Field {
  name: string
  label?: string
  type?: 'text' | 'number' | 'date' | 'select' | 'checkbox' | 'textarea' | 'password'
  options?: (string | { value: string; label: string })[]
  required?: boolean
  placeholder?: string
}

export type FormValues = Record<string, string | boolean>

/** Converts form strings into typed JSON: numbers for number fields, null for blanks. */
export const toPayload = (fields: Field[], values: FormValues) =>
  Object.fromEntries(fields.map((f) => {
    const v = values[f.name]
    if (f.type === 'checkbox') return [f.name, !!v]
    if (v === undefined || v === '') return [f.name, null]
    return [f.name, f.type === 'number' ? Number(v) : v]
  }))

export function Form({ fields, initial = {}, submitLabel = 'Save', onSubmit, busy, inline }: {
  fields: Field[]
  initial?: FormValues
  submitLabel?: string
  onSubmit: (payload: Record<string, unknown>, values: FormValues) => void | Promise<unknown>
  busy?: boolean
  inline?: boolean
}) {
  const [values, setValues] = useState<FormValues>(initial)
  const set = (k: string, v: string | boolean) => setValues((s) => ({ ...s, [k]: v }))
  const submit = (e: FormEvent) => {
    e.preventDefault()
    onSubmit(toPayload(fields, values), values)
  }
  return (
    <form className={inline ? 'form inline' : 'form'} onSubmit={submit}>
      {fields.map((f) => {
        const v = values[f.name]
        const label = f.label ?? f.name.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase())
        return (
          <label key={f.name} className={f.type === 'checkbox' ? 'check' : ''}>
            <span>{label}{f.required && ' *'}</span>
            {f.type === 'select' ? (
              <select value={String(v ?? '')} required={f.required} onChange={(e) => set(f.name, e.target.value)}>
                <option value="">—</option>
                {f.options?.map((o) => typeof o === 'string'
                  ? <option key={o} value={o}>{o}</option>
                  : <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            ) : f.type === 'checkbox' ? (
              <input type="checkbox" checked={!!v} onChange={(e) => set(f.name, e.target.checked)} />
            ) : f.type === 'textarea' ? (
              <textarea value={String(v ?? '')} required={f.required} onChange={(e) => set(f.name, e.target.value)} />
            ) : (
              <input type={f.type ?? 'text'} step={f.type === 'number' ? 'any' : undefined} value={String(v ?? '')}
                required={f.required} placeholder={f.placeholder} onChange={(e) => set(f.name, e.target.value)} />
            )}
          </label>
        )
      })}
      <button type="submit" disabled={busy}>{busy ? 'Working…' : submitLabel}</button>
    </form>
  )
}

export function Pager({ page, totalPages, onPage }: { page: number; totalPages: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) return null
  return (
    <div className="pager">
      <button className="secondary" disabled={page <= 0} onClick={() => onPage(page - 1)}>Prev</button>
      <span>Page {page + 1} / {totalPages}</span>
      <button className="secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
    </div>
  )
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="stat">
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  )
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <header className="card-head">
          <h3>{title}</h3>
          <button className="secondary" onClick={onClose}>Close</button>
        </header>
        {children}
      </div>
    </div>
  )
}
