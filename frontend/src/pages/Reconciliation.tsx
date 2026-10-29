import { useState } from 'react'
import { api, qs, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, useAction, useLoad } from '../components/ui'
import { useAuth } from '../auth'

export default function Reconciliation() {
  const { hasRole } = useAuth()
  const [status, setStatus] = useState<string>('OPEN')
  const list = useLoad(() => api.get<Row[]>(`/reconciliation${qs({ status })}`), [status])
  const checks = useLoad(() => api.get<Row[] | string[]>('/reconciliation/checks'))
  const a = useAction()
  return (
    <>
      <h2>Reconciliation</h2>
      <Alert error={a.error ?? list.error} message={a.message} />
      <Card title="Controls" actions={hasRole('FINANCE') && <button disabled={a.busy}
        onClick={() => a.run(() => api.post<Row>('/reconciliation/run'), (r) => `Checks complete: ${Object.entries(r).map(([k, v]) => `${k}=${v}`).join(', ')}`).then(list.reload)}>Run all checks</button>}>
        <p className="muted">{Array.isArray(checks.data) ? checks.data.map((c) => (typeof c === 'string' ? c : String(c.code ?? c.name))).join(' · ') : ''}</p>
        <Form inline submitLabel="Filter" initial={{ status }} fields={[{ name: 'status', type: 'select', options: ['OPEN', 'RESOLVED'] }]}
          onSubmit={(p) => setStatus(String(p.status ?? ''))} />
      </Card>
      <Card title={`Discrepancies (${list.data?.length ?? 0})`}>
        <DataTable rows={list.data} empty="No discrepancies — books reconcile" columns={list.data?.[0] ? [...Object.keys(list.data[0]).slice(0, 10).map((key) => ({ key })),
          { key: '_', label: '', render: (r: Row) => hasRole('FINANCE') && r.status === 'OPEN' && <button className="secondary" onClick={() => {
            const note = window.prompt('Resolution note')
            if (note) a.run(() => api.post(`/reconciliation/${r.discrepancy_id}/resolve`, { note }), 'Resolved').then(list.reload)
          }}>Resolve</button> }] : undefined} />
      </Card>
    </>
  )
}
