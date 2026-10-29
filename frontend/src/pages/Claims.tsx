import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, qs, type Page, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, Modal, Pager, Stat, fmt, money, useAction, useLoad } from '../components/ui'

const STATUSES = ['REGISTERED', 'ASSIGNED', 'UNDER_INVESTIGATION', 'PENDING_INFO', 'ASSESSED', 'PENDING_APPROVAL', 'APPROVED', 'PARTIAL', 'REJECTED', 'SETTLED', 'CLOSED', 'REOPENED']

export default function Claims() {
  const nav = useNavigate()
  const [filter, setFilter] = useState<Record<string, string | number | undefined>>({ page: 0 })
  const list = useLoad(() => api.get<Page<Row>>(`/claims${qs({ ...filter, size: 20 })}`), [filter])
  const summary = useLoad(() => api.get<Row>('/claims/summary'))
  const cats = useLoad(() => api.get<Row[]>('/reference/claim-categories'))
  const [creating, setCreating] = useState(false)
  const a = useAction()
  return (
    <>
      <h2>Claims</h2>
      <Alert error={list.error} />
      <div className="stats">
        {summary.data && Object.entries(summary.data).filter(([, v]) => typeof v !== 'object' || v === null)
          .map(([k, v]) => <Stat key={k} label={k.replace(/([a-z])([A-Z])/g, '$1 $2')} value={/amount|paid|reserve/i.test(k) ? money(v) : fmt(v)} />)}
      </div>
      <Card title="Search" actions={<button onClick={() => setCreating(true)}>Register claim (FNOL)</button>}>
        <Form inline submitLabel="Search"
          fields={[{ name: 'q', label: 'Claim / policy no' }, { name: 'status', type: 'select', options: STATUSES }, { name: 'policyId', type: 'number' }, { name: 'adjusterId', type: 'number' }]}
          onSubmit={(p) => setFilter({ ...(p as Record<string, string>), page: 0 })} />
      </Card>
      <Card title={`Results (${list.data?.totalElements ?? 0})`}>
        <DataTable rows={list.data?.content} onRowClick={(r) => nav(`/claims/${r.id}`)}
          columns={[{ key: 'claimNo', label: 'Claim no' }, { key: 'policyNo', label: 'Policy' }, { key: 'categoryCode', label: 'Category' },
            { key: 'incidentDt', label: 'Incident' }, { key: 'claimedAmount', label: 'Claimed' }, { key: 'approvedAmount', label: 'Approved' },
            { key: 'priority' }, { key: 'fraudScore', label: 'Fraud score' }, { key: 'slaDueDt', label: 'SLA due' }, { key: 'displayStatus', label: 'Status' }]} />
        <Pager page={Number(filter.page)} totalPages={list.data?.totalPages ?? 0} onPage={(page) => setFilter({ ...filter, page })} />
      </Card>
      {creating && (
        <Modal title="First notice of loss" onClose={() => setCreating(false)}>
          <Alert error={a.error} />
          <Form busy={a.busy} submitLabel="Register" initial={{ incidentDt: new Date().toISOString().slice(0, 10) }}
            fields={[{ name: 'policyId', type: 'number', required: true },
              { name: 'categoryCode', type: 'select', required: true, options: (cats.data ?? []).map((c) => ({ value: String(c.category_code), label: `${c.line_of_business} — ${c.description}` })) },
              { name: 'subtypeCode' }, { name: 'incidentDt', type: 'date', required: true }, { name: 'reportedDt', type: 'date' },
              { name: 'claimedAmount', type: 'number', required: true }, { name: 'description', type: 'textarea', required: true }]}
            onSubmit={(p) => a.run(() => api.post<Row>('/claims', p)).then((r) => r && nav(`/claims/${(r.claim as Row).id}`))} />
        </Modal>
      )}
    </>
  )
}
