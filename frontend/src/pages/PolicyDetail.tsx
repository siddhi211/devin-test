import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, KeyValues, Modal, money, useAction, useLoad, type FormValues } from '../components/ui'
import { useAuth } from '../auth'
import { POLICY_FIELDS } from './Policies'

const TABS = ['Status history', 'Versions', 'Endorsements', 'Underwriting', 'Claims', 'Renewals', 'Cancellations', 'Workflows'] as const
const TAB_KEY: Record<(typeof TABS)[number], string> = {
  'Status history': 'statusHistory', Versions: 'versions', Endorsements: 'endorsements', Underwriting: 'uwDecisions',
  Claims: 'claims', Renewals: 'renewals', Cancellations: 'cancellations', Workflows: 'workflows',
}

export default function PolicyDetail() {
  const { id } = useParams()
  const { hasRole } = useAuth()
  const d = useLoad(() => api.get<Row>(`/policies/${id}`), [id])
  const products = useLoad(() => api.get<Row[]>('/products'))
  const reasons = useLoad(() => api.get<Row[]>('/lifecycle/cancel-reasons'))
  const [tab, setTab] = useState<(typeof TABS)[number]>('Status history')
  const [modal, setModal] = useState<'edit' | 'endorse' | 'cancel' | 'withdraw' | null>(null)
  const [preview, setPreview] = useState<Row | null>(null)
  const [uwResult, setUwResult] = useState<Row | null>(null)
  const a = useAction()
  const base = `/policies/${id}`
  const act = <T,>(fn: () => Promise<T>, msg: string) => a.run(fn, msg).then((r) => { if (r !== undefined) { d.reload(); setModal(null) } return r })
  if (!d.data) return <Alert error={d.error} />
  const p = d.data.policy as Row
  const customer = d.data.customer as Row
  const product = d.data.product as Row
  const status = String(p.status)
  const lifecycle = d.data.lifecycle as Row | null
  const initial = Object.fromEntries(POLICY_FIELDS(null).map((f) => [f.name, f.name === 'smoker' ? p.smokerFlag === 'Y' : p[f.name] == null ? '' : String(p[f.name])])) as FormValues

  return (
    <>
      <h2>Policy {String(p.policyNo)} <span className="muted">· {String(product?.productName ?? '')}</span></h2>
      <Alert error={a.error} message={a.message} />
      <Card title="Policy" actions={<>
        {status === 'DRAFT' && hasRole('CSR', 'UNDERWRITER') && <>
          <button className="secondary" onClick={() => setModal('edit')}>Edit</button>
          <button onClick={() => act(() => api.post<Row>(`${base}/submit`), 'Submitted for underwriting').then((r) => r && setUwResult(r))}>Submit</button>
        </>}
        {status === 'UW_APPROVED' && hasRole('CSR', 'UNDERWRITER') && <button onClick={() => act(() => api.post(`${base}/issue`), 'Policy issued')}>Issue policy</button>}
        {['DRAFT', 'SUBMITTED', 'PENDING_UW', 'UW_APPROVED'].includes(status) && <button className="secondary" onClick={() => setModal('withdraw')}>Withdraw</button>}
        {['ACTIVE', 'GRACE'].includes(status) && <>
          <button className="secondary" onClick={() => setModal('endorse')}>Endorse</button>
          <button className="secondary" onClick={() => act(() => api.post(`/lifecycle/policies/${id}/renewal-offer`), 'Renewal offer generated')}>Renewal offer</button>
          <button className="danger" onClick={() => { setPreview(null); setModal('cancel') }}>Cancel policy</button>
        </>}
        <Link to={`/billing?policyId=${id}`}><button className="secondary">Billing</button></Link>
      </>}>
        <KeyValues data={p} keys={['status', 'sumInsured', 'annualPremium', 'installmentPremium', 'paymentFrequency', 'termMonths', 'startDt', 'endDt', 'issueDt', 'riskClass', 'smokerFlag', 'occupationCode', 'branchCd', 'versionNo', 'renewalCount']} />
        <p>
          Customer: <Link to={`/customers/${customer.id}`}>{String(customer.name)} ({String(customer.customerNo)})</Link> ·
          Outstanding: <strong>{money(d.data.outstanding)}</strong> ·
          Allowed transitions: {(d.data.allowedTransitions as string[]).join(', ') || '—'}
        </p>
        {lifecycle && ['ACTIVE', 'GRACE'].includes(status) && (
          <label className="check">
            <input type="checkbox" checked={lifecycle.auto_renew === 'Y'}
              onChange={(e) => act(() => api.post(`/lifecycle/policies/${id}/auto-renew`, { autoRenew: e.target.checked }), 'Auto-renew updated')} /> Auto-renew
          </label>
        )}
      </Card>
      {uwResult && <Card title="Underwriting result"><KeyValues data={uwResult.evaluation as Row ?? uwResult} /><pre>{JSON.stringify((uwResult.evaluation as Row | undefined)?.findings ?? [], null, 1)}</pre></Card>}
      <div className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}</div>
      <Card><DataTable rows={d.data[TAB_KEY[tab]] as Row[]} /></Card>

      {modal === 'edit' && (
        <Modal title="Edit draft policy" onClose={() => setModal(null)}>
          <Form fields={POLICY_FIELDS(products.data)} initial={initial} busy={a.busy} onSubmit={(v) => act(() => api.put(base, v), 'Policy updated')} />
        </Modal>
      )}
      {modal === 'withdraw' && (
        <Modal title="Withdraw policy" onClose={() => setModal(null)}>
          <Form fields={[{ name: 'reason', required: true }]} busy={a.busy} submitLabel="Withdraw" onSubmit={(v) => act(() => api.post(`${base}/withdraw`, v), 'Policy withdrawn')} />
        </Modal>
      )}
      {modal === 'endorse' && (
        <Modal title="Mid-term endorsement" onClose={() => setModal(null)}>
          <Alert error={a.error} />
          <Form busy={a.busy} submitLabel="Apply endorsement"
            fields={[{ name: 'endorsementType', type: 'select', options: ['SUM_INSURED', 'ADDRESS', 'BENEFICIARY', 'OTHER'], required: true },
              { name: 'description', required: true }, { name: 'newSumInsured', type: 'number' }]}
            onSubmit={(v) => act(() => api.post(`${base}/endorsements`, v), 'Endorsement applied')} />
        </Modal>
      )}
      {modal === 'cancel' && (
        <Modal title="Cancel policy" onClose={() => setModal(null)}>
          <Alert error={a.error} message={a.message} />
          <Form busy={a.busy} submitLabel="Preview refund"
            fields={[{ name: 'cancelType', type: 'select', options: ['INSURED', 'INSURER', 'NON_PAYMENT'], required: true },
              { name: 'reasonCode', type: 'select', required: true, options: (reasons.data ?? []).map((r) => ({ value: String(r.reason_code), label: String(r.description ?? r.reason_code) })) },
              { name: 'effectiveDt', type: 'date' }, { name: 'refundMethod', type: 'select', options: ['PRORATA', 'SHORTRATE', 'FULL', 'NONE'] }, { name: 'notes' }]}
            onSubmit={(v) => a.run(() => api.post<Row>('/lifecycle/cancellations/preview', { ...v, policyId: Number(id) })).then((r) => r && setPreview({ ...r, _req: v }))} />
          {preview && (
            <Card title="Refund preview">
              <KeyValues data={preview} />
              <button className="danger" disabled={a.busy}
                onClick={() => act(() => api.post('/lifecycle/cancellations', { ...(preview._req as Row), policyId: Number(id) }), 'Cancellation requested')}>Confirm cancellation</button>
            </Card>
          )}
        </Modal>
      )}
    </>
  )
}
