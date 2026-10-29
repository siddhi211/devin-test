import { useState } from 'react'
import { api, qs, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, Stat, fmt, useAction, useLoad } from '../components/ui'

export default function Notifications() {
  const [filter, setFilter] = useState<Record<string, unknown>>({})
  const list = useLoad(() => api.get<Row[] | { content: Row[] }>(`/notifications${qs(filter as Record<string, string>)}`), [filter])
  const summary = useLoad(() => api.get<Row>('/notifications/summary'))
  const templates = useLoad(() => api.get<Row[]>('/notifications/templates'))
  const a = useAction()
  const rows = Array.isArray(list.data) ? list.data : list.data?.content
  const refresh = () => { list.reload(); summary.reload() }
  return (
    <>
      <h2>Notifications</h2>
      <Alert error={a.error ?? list.error} message={a.message} />
      <div className="stats">
        {summary.data && Object.entries(summary.data).filter(([, v]) => typeof v !== 'object').map(([k, v]) => <Stat key={k} label={k} value={fmt(v)} />)}
      </div>
      <Card title="Outbox" actions={<button onClick={() => a.run(() => api.post<Row>('/notifications/flush'), (r) => `Flushed: ${JSON.stringify(r)}`).then(refresh)}>Flush outbox now</button>}>
        <Form inline submitLabel="Filter" fields={[{ name: 'status', type: 'select', options: ['PENDING', 'SENT', 'FAILED', 'DEAD'] }]}
          onSubmit={(p) => setFilter(p)} />
        <DataTable rows={rows} columns={rows?.[0] ? [...Object.keys(rows[0]).filter((k) => k !== 'body').slice(0, 10).map((key) => ({ key })),
          { key: '_', label: '', render: (r: Row) => (r.status === 'DEAD' || r.status === 'FAILED')
            ? <button className="secondary" onClick={() => a.run(() => api.post(`/notifications/${r.outbox_id}/requeue`), 'Requeued').then(refresh)}>Requeue</button> : null }] : undefined} />
      </Card>
      <Card title="Templates"><DataTable rows={templates.data} /></Card>
    </>
  )
}
