import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Modal, useAction, useLoad } from '../components/ui'
import { Sections } from '../components/Sections'
import { useAuth } from '../auth'

const TABS = ['Renewal candidates', 'Renewal offers', 'Cancellations', 'Cancellation reasons'] as const

export default function Lifecycle() {
  const { hasRole } = useAuth()
  const [tab, setTab] = useState<(typeof TABS)[number]>('Renewal candidates')
  const candidates = useLoad(() => api.get<Row[]>('/lifecycle/renewals/candidates'))
  const renewals = useLoad(() => api.get<Row[]>('/lifecycle/renewals'))
  const cancellations = useLoad(() => api.get<Row[]>('/lifecycle/cancellations'))
  const reasons = useLoad(() => api.get<Row[]>('/lifecycle/cancel-reasons'))
  const [detail, setDetail] = useState<Row | null>(null)
  const a = useAction()
  const refresh = () => { candidates.reload(); renewals.reload(); cancellations.reload() }
  const act = (fn: () => Promise<unknown>, msg: string) => a.run(fn, msg).then((r) => { if (r !== undefined) { refresh(); setDetail(null) } })
  const policyLink = (r: Row) => <Link to={`/policies/${r.policy_id}`} onClick={(e) => e.stopPropagation()}>{String(r.policy_no ?? r.policy_id)}</Link>

  return (
    <>
      <h2>Renewals &amp; Cancellations</h2>
      <Alert error={a.error} message={a.message} />
      <div className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}</div>
      {tab === 'Renewal candidates' && (
        <Card title="Policies expiring within the renewal window">
          <DataTable rows={candidates.data} empty="No policies due for renewal" columns={candidates.data?.[0] ? [
            { key: 'policy_no', label: 'Policy', render: policyLink }, ...Object.keys(candidates.data[0]).filter((k) => !['policy_no', 'policy_id'].includes(k)).slice(0, 8).map((key) => ({ key })),
            { key: '_', label: '', render: (r: Row) => <button onClick={() => act(() => api.post(`/lifecycle/policies/${r.policy_id}/renewal-offer`), 'Renewal offer generated')}>Generate offer</button> }] : undefined} />
        </Card>
      )}
      {tab === 'Renewal offers' && (
        <Card title="Renewal offers">
          <DataTable rows={renewals.data} onRowClick={(r) => api.get<Row>(`/lifecycle/renewals/${r.renewal_id}`).then(setDetail)}
            columns={renewals.data?.[0] ? [{ key: 'policy_no', label: 'Policy', render: policyLink }, ...Object.keys(renewals.data[0]).filter((k) => !['policy_no', 'policy_id'].includes(k)).slice(0, 10).map((key) => ({ key })),
              { key: '_', label: '', render: (r: Row) => r.status === 'OFFERED' && <span className="actions">
                <button onClick={(e) => { e.stopPropagation(); act(() => api.post(`/lifecycle/renewals/${r.renewal_id}/decision`, { decision: 'ACCEPT' }), 'Renewal accepted') }}>Accept</button>
                <button className="secondary" onClick={(e) => { e.stopPropagation(); act(() => api.post(`/lifecycle/renewals/${r.renewal_id}/decision`, { decision: 'DECLINE' }), 'Renewal declined') }}>Decline</button>
              </span> }] : undefined} />
        </Card>
      )}
      {tab === 'Cancellations' && (
        <Card title="Cancellation requests (start a cancellation from the policy page)">
          <DataTable rows={cancellations.data} onRowClick={(r) => api.get<Row>(`/lifecycle/cancellations/${r.cancellation_id}`).then(setDetail)}
            columns={cancellations.data?.[0] ? [{ key: 'policy_no', label: 'Policy', render: policyLink }, ...Object.keys(cancellations.data[0]).filter((k) => !['policy_no', 'policy_id'].includes(k)).slice(0, 10).map((key) => ({ key })),
              { key: '_', label: '', render: (r: Row) => hasRole('CSR', 'MANAGER') && ['COMPLETED', 'APPROVED'].includes(String(r.status)) && <span className="actions">
                <button className="secondary" onClick={(e) => { e.stopPropagation(); act(() => api.post(`/lifecycle/cancellations/${r.cancellation_id}/reinstate`, { payArrears: true }), 'Policy reinstated') }}>Reinstate</button>
              </span> }] : undefined} />
        </Card>
      )}
      {tab === 'Cancellation reasons' && <Card><DataTable rows={reasons.data} /></Card>}
      {detail && <Modal title="Details" onClose={() => setDetail(null)}><Sections data={detail} /></Modal>}
    </>
  )
}
