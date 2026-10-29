import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, qs, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, KeyValues, Stat, fmt, money, useAction, useLoad } from '../components/ui'
import { useAuth } from '../auth'

const TABS = ['Policy account', 'Payments', 'Refunds', 'Ledger', 'Trial balance'] as const

export default function Billing() {
  const { hasRole } = useAuth()
  const [params, setParams] = useSearchParams()
  const policyId = params.get('policyId')
  const [tab, setTab] = useState<(typeof TABS)[number]>('Policy account')
  const summary = useLoad(() => api.get<Row>('/billing/summary'))
  const account = useLoad(() => (policyId ? api.get<Row>(`/billing/policies/${policyId}`) : Promise.resolve(null)), [policyId])
  const payments = useLoad(() => api.get<Row[]>('/billing/payments'))
  const refunds = useLoad(() => api.get<Row[]>('/billing/refunds'))
  const ledger = useLoad(() => api.get<Row[]>(`/billing/ledger${qs({ policyId })}`), [policyId])
  const trial = useLoad(() => api.get<Row[]>('/billing/trial-balance'))
  const a = useAction()
  const refresh = () => { summary.reload(); account.reload(); payments.reload(); refunds.reload(); ledger.reload(); trial.reload() }
  const act = (fn: () => Promise<unknown>, msg: string) => a.run(fn, msg).then((r) => { if (r !== undefined) refresh() })
  const finance = hasRole('FINANCE')

  return (
    <>
      <h2>Billing &amp; Payments</h2>
      <Alert error={a.error ?? summary.error ?? account.error} message={a.message} />
      <div className="stats">
        {summary.data && Object.entries(summary.data).map(([k, v]) => <Stat key={k} label={k.replace(/([a-z])([A-Z])/g, '$1 $2')} value={/count|installments|retries|payments30d/i.test(k) ? fmt(v) : money(v)} />)}
      </div>
      <div className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}</div>

      {tab === 'Policy account' && (
        <>
          <Card title="Find policy account">
            <Form inline submitLabel="Open" initial={{ policyId: policyId ?? '' }} fields={[{ name: 'policyId', type: 'number', label: 'Policy ID', required: true }]}
              onSubmit={(p) => setParams({ policyId: String(p.policyId) })} />
          </Card>
          {account.data && (
            <>
              <Card title={<>Policy <Link to={`/policies/${policyId}`}>{String(account.data.policyNo)}</Link></>}>
                <KeyValues data={account.data} keys={['policyStatus', 'totalDue', 'totalPaid', 'outstanding', 'overdue', 'lateFeesOutstanding', 'creditBalance']} />
              </Card>
              {hasRole('FINANCE', 'CSR') && (
                <Card title="Take payment">
                  <Form inline busy={a.busy} submitLabel="Post payment"
                    initial={{ amount: String((account.data.schedule as Row[]).find((s) => s.status !== 'PAID')?.amount_due ?? ''), method: 'CARD' }}
                    fields={[{ name: 'amount', type: 'number', required: true }, { name: 'method', type: 'select', options: ['CARD', 'BANK_TRANSFER', 'CHECK', 'CASH', 'DIRECT_DEBIT'], required: true },
                      { name: 'instrumentRef', label: 'Card / account / cheque ref', placeholder: 'card ending 0002 = decline' }]}
                    onSubmit={(p) => a.run(() => api.post<Row>(`/billing/policies/${policyId}/payments`, { ...p, idempotencyKey: crypto.randomUUID() }),
                      (r) => `Payment ${r.status}${r.receiptNo ? ` — receipt ${r.receiptNo}` : ''}${r.message ? ` — ${r.message}` : ''}`).then(refresh)} />
                </Card>
              )}
              <Card title="Premium schedule">
                <DataTable rows={account.data.schedule as Row[]} columns={(account.data.schedule as Row[])[0] ? [...Object.keys((account.data.schedule as Row[])[0]).slice(0, 10).map((key) => ({ key })),
                  { key: '_', label: '', render: (r: Row) => finance && Number(r.late_fee ?? 0) > Number(r.late_fee_paid ?? 0) &&
                    <button className="secondary" onClick={() => act(() => api.post(`/billing/schedules/${r.schedule_id}/waive-late-fee`, { reason: 'Goodwill waiver' }), 'Late fee waived')}>Waive fee</button> }] : undefined} />
              </Card>
              <Card title="Payments"><DataTable rows={account.data.payments as Row[]} /></Card>
              <Card title="Retry queue"><DataTable rows={account.data.retries as Row[]} empty="No retries" /></Card>
            </>
          )}
        </>
      )}
      {tab === 'Payments' && (
        <Card title="Recent payments">
          <DataTable rows={payments.data} columns={payments.data?.[0] ? [...Object.keys(payments.data[0]).slice(0, 10).map((key) => ({ key })),
            { key: '_', label: '', render: (r: Row) => finance && r.status === 'POSTED' &&
              <button className="secondary" onClick={() => act(() => api.post(`/billing/payments/${r.payment_id}/reverse`, { reason: 'Bounced / chargeback' }), 'Payment reversed')}>Reverse</button> }] : undefined} />
        </Card>
      )}
      {tab === 'Refunds' && (
        <Card title="Refunds">
          <DataTable rows={refunds.data} empty="No refunds" columns={refunds.data?.[0] ? [...Object.keys(refunds.data[0]).slice(0, 10).map((key) => ({ key })),
            { key: '_', label: '', render: (r: Row) => finance && r.status === 'PENDING' &&
              <button onClick={() => act(() => api.post(`/billing/refunds/${r.refund_id}/pay`), 'Refund paid')}>Pay</button> }] : undefined} />
        </Card>
      )}
      {tab === 'Ledger' && <Card title="Journal entries"><DataTable rows={ledger.data} /></Card>}
      {tab === 'Trial balance' && <Card title="Trial balance"><DataTable rows={trial.data} /></Card>}
    </>
  )
}
