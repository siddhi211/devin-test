import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type Row } from '../api/client'
import { Alert, Badge, Card, DataTable, Form, KeyValues, Modal, money, useAction, useLoad } from '../components/ui'
import { useAuth } from '../auth'

const TABS = ['Risk flags', 'Assessments', 'Settlements', 'Notes', 'Documents', 'History', 'Workflows'] as const
type Action = 'assess' | 'decide' | 'settle' | 'move' | 'reopen' | null

export default function ClaimDetail() {
  const { id } = useParams()
  const { hasRole } = useAuth()
  const d = useLoad(() => api.get<Row>(`/claims/${id}`), [id])
  const adjusters = useLoad(() => api.get<Row[]>('/users/by-role/ADJUSTER'))
  const [tab, setTab] = useState<(typeof TABS)[number]>('Risk flags')
  const [modal, setModal] = useState<Action>(null)
  const [trace, setTrace] = useState<Row | null>(null)
  const a = useAction()
  const base = `/claims/${id}`
  const act = <T,>(fn: () => Promise<T>, msg: string) => a.run(fn, msg).then((r) => { if (r !== undefined) { d.reload(); setModal(null) } return r })
  if (!d.data) return <Alert error={d.error} />
  const c = d.data.claim as Row
  const policy = d.data.policy as Row
  const status = String(c.status)
  const allowed = d.data.allowedTransitions as string[]
  const handler = hasRole('ADJUSTER', 'MANAGER')

  return (
    <>
      <h2>Claim {String(c.claimNo)} <Badge value={d.data.displayStatus} /></h2>
      <Alert error={a.error} message={a.message} />
      <Card title="Claim" actions={<>
        {handler && ['REGISTERED', 'REOPENED', 'ASSIGNED'].includes(status) && hasRole('MANAGER') &&
          <button className="secondary" onClick={() => act(() => api.post(`${base}/assign`, {}), 'Auto-assigned')}>Auto-assign</button>}
        {handler && allowed.length > 0 && <button className="secondary" onClick={() => setModal('move')}>Change status</button>}
        {handler && ['ASSIGNED', 'UNDER_INVESTIGATION', 'PENDING_INFO', 'REOPENED'].includes(status) && <button onClick={() => setModal('assess')}>Assess</button>}
        {handler && status === 'ASSESSED' && <button onClick={() => setModal('decide')}>Decide</button>}
        {hasRole('FINANCE', 'MANAGER') && ['APPROVED', 'PARTIAL'].includes(status) && <button onClick={() => setModal('settle')}>Settle</button>}
        {handler && ['REJECTED', 'SETTLED', 'CLOSED'].includes(status) && <button className="secondary" onClick={() => setModal('reopen')}>Reopen</button>}
      </>}>
        <KeyValues data={c} keys={['categoryCode', 'incidentDt', 'reportedDt', 'claimedAmount', 'netAmount', 'approvedAmount', 'settledAmount', 'priority', 'fraudScore', 'slaDueDt', 'slaBreached', 'reopenCount', 'decisionReason']} />
        <p>
          Policy <Link to={`/policies/${policy.policy_id}`}>{String(policy.policy_no)}</Link> · sum insured {money(policy.sum_insured)} ·
          Adjuster: {d.data.adjuster ? String((d.data.adjuster as Row).full_name ?? (d.data.adjuster as Row).username) : 'unassigned'}
        </p>
        <p className="muted">{String(c.description ?? '')}</p>
        {hasRole('MANAGER') && ['REGISTERED', 'ASSIGNED', 'REOPENED'].includes(status) && (
          <Form inline submitLabel="Assign to" busy={a.busy}
            fields={[{ name: 'adjusterId', type: 'select', required: true, options: (adjusters.data ?? []).map((u) => ({ value: String(u.id ?? u.user_id), label: String(u.fullName ?? u.full_name ?? u.username) })) }]}
            onSubmit={(p) => act(() => api.post(`${base}/assign`, { adjusterId: Number(p.adjusterId) }), 'Assigned')} />
        )}
      </Card>
      {trace && <Card title="Assessment calculation"><KeyValues data={{ netAmount: trace.netAmount }} /><pre>{(trace.trace as string[] ?? []).join('\n')}</pre></Card>}

      <div className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}</div>
      {tab === 'Risk flags' && (
        <Card title="Fraud / risk indicators">
          <DataTable rows={d.data.riskFlags as Row[]} empty="No risk flags" columns={(d.data.riskFlags as Row[])[0] ? [...Object.keys((d.data.riskFlags as Row[])[0]).map((key) => ({ key })),
            { key: '_', label: '', render: (r: Row) => handler && r.cleared_flag !== 'Y' && r.status !== 'CLEARED' &&
              <button className="secondary" onClick={() => act(() => api.post(`${base}/risk-flags/${r.flag_id}/clear`, { reason: 'Investigated — no concern' }), 'Flag cleared')}>Clear</button> }] : undefined} />
        </Card>
      )}
      {tab === 'Assessments' && <Card><DataTable rows={d.data.assessments as Row[]} /></Card>}
      {tab === 'Settlements' && (
        <>
          <Card title="Settlements">
            <DataTable rows={d.data.settlements as Row[]} columns={(d.data.settlements as Row[])[0] ? [...Object.keys((d.data.settlements as Row[])[0]).slice(0, 10).map((key) => ({ key })),
              { key: '_', label: '', render: (r: Row) => hasRole('FINANCE') && r.status !== 'REVERSED' &&
                <button className="secondary" onClick={() => act(() => api.post(`${base}/settlements/${r.settlement_id}/reverse`, { reason: 'Payment returned' }), 'Payout reversed')}>Reverse</button> }] : undefined} />
          </Card>
          <Card title="Payout events"><DataTable rows={d.data.payoutEvents as Row[]} /></Card>
        </>
      )}
      {tab === 'Notes' && (
        <Card title="Notes">
          <Form inline busy={a.busy} submitLabel="Add note" fields={[{ name: 'noteType', type: 'select', options: ['GENERAL', 'INVESTIGATION', 'CUSTOMER_CONTACT', 'INTERNAL'] }, { name: 'text', required: true }]}
            onSubmit={(p) => act(() => api.post(`${base}/notes`, p), 'Note added')} />
          <DataTable rows={d.data.notes as Row[]} />
        </Card>
      )}
      {tab === 'Documents' && (
        <Card title="Documents">
          <Form inline busy={a.busy} submitLabel="Attach" fields={[{ name: 'docType', type: 'select', options: ['PHOTO', 'INVOICE', 'POLICE_REPORT', 'MEDICAL_REPORT', 'ESTIMATE', 'OTHER'], required: true }, { name: 'fileName', required: true }]}
            onSubmit={(p) => act(() => api.post(`${base}/documents`, p), 'Document attached')} />
          <DataTable rows={d.data.documents as Row[]} />
        </Card>
      )}
      {tab === 'History' && <Card><DataTable rows={d.data.history as Row[]} /></Card>}
      {tab === 'Workflows' && <Card><DataTable rows={d.data.workflows as Row[]} /></Card>}

      {modal === 'move' && (
        <Modal title="Change claim status" onClose={() => setModal(null)}>
          <Form busy={a.busy} fields={[{ name: 'status', type: 'select', options: allowed, required: true }, { name: 'reason' }]}
            onSubmit={(p) => act(() => api.post(`${base}/status`, p), 'Status changed')} />
        </Modal>
      )}
      {modal === 'assess' && (
        <Modal title="Assess claim" onClose={() => setModal(null)}>
          <Alert error={a.error} />
          <p className="muted">Net = (claimed − excluded) × (1 − depreciation%) − salvage − deductible, × coverage%, capped at remaining sum insured.</p>
          <Form busy={a.busy} submitLabel="Calculate & save"
            fields={[{ name: 'excludedAmount', type: 'number' }, { name: 'depreciationPct', type: 'number' }, { name: 'salvageAmount', type: 'number' }, { name: 'note', type: 'textarea' }]}
            onSubmit={(p) => act(() => api.post<Row>(`${base}/assess`, p), 'Assessment saved').then((r) => r && setTrace(r))} />
        </Modal>
      )}
      {modal === 'decide' && (
        <Modal title="Claim decision" onClose={() => setModal(null)}>
          <Alert error={a.error} />
          <p className="muted">Approvals above your authority (or with fraud score ≥ 50) are routed to a manager for four-eyes approval. An approved amount below the assessed net is a partial approval.</p>
          <Form busy={a.busy} submitLabel="Submit"
            fields={[{ name: 'decision', type: 'select', options: ['APPROVE', 'REJECT'], required: true }, { name: 'approvedAmount', type: 'number' }, { name: 'reason', type: 'textarea', required: true }]}
            onSubmit={(p) => act(() => api.post<Row>(`${base}/decide`, p), 'Decision recorded')} />
        </Modal>
      )}
      {modal === 'settle' && (
        <Modal title="Settle claim" onClose={() => setModal(null)}>
          <Alert error={a.error} />
          <Form busy={a.busy} submitLabel="Pay out" initial={{ method: 'TRANSFER', amount: String(c.approvedAmount ?? '') }}
            fields={[{ name: 'method', type: 'select', options: ['TRANSFER', 'CHECK', 'CASH'], required: true }, { name: 'payeeName' }, { name: 'amount', type: 'number' }]}
            onSubmit={(p) => act(() => api.post(`${base}/settle`, p), 'Claim settled')} />
        </Modal>
      )}
      {modal === 'reopen' && (
        <Modal title="Reopen claim" onClose={() => setModal(null)}>
          <Alert error={a.error} />
          <Form busy={a.busy} submitLabel="Reopen" fields={[{ name: 'reason', required: true }]} onSubmit={(p) => act(() => api.post(`${base}/reopen`, p), 'Claim reopened')} />
        </Modal>
      )}
    </>
  )
}
