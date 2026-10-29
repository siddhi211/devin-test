import { useNavigate } from 'react-router-dom'
import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Stat, fmt, money, useLoad } from '../components/ui'

const label = (k: string) => k.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase())

export default function Dashboard() {
  const nav = useNavigate()
  const { data, error } = useLoad(() => api.get<Row>('/dashboard'))
  const tasks = useLoad(() => api.get<Row[]>('/workflow/inbox'))
  const scalars = data ? Object.entries(data).filter(([, v]) => v === null || typeof v !== 'object') : []
  const lists = data ? Object.entries(data).filter(([, v]) => Array.isArray(v)) : []
  return (
    <>
      <h2>Dashboard</h2>
      <Alert error={error} />
      <div className="stats">
        {scalars.map(([k, v]) => (
          <Stat key={k} label={label(k)} value={/amount|premium|paid|collected|outstanding|reserve/i.test(k) ? money(v) : fmt(v)} />
        ))}
      </div>
      <Card title={`My open tasks (${tasks.data?.length ?? 0})`}>
        <DataTable rows={tasks.data} onRowClick={() => nav('/workflow')}
          columns={[{ key: 'step_name', label: 'Task' }, { key: 'subject_type' }, { key: 'subject_ref', label: 'Reference' },
            { key: 'amount' }, { key: 'requested_by' }, { key: 'status' }, { key: 'sla_due_dt', label: 'SLA due' }]} />
      </Card>
      <div className="grid2">
        {lists.map(([k, v]) => <Card key={k} title={label(k)}><DataTable rows={v as Row[]} /></Card>)}
      </div>
    </>
  )
}
