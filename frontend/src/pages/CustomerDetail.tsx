import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, type Row } from '../api/client'
import { Alert, Badge, Card, DataTable, Form, KeyValues, Modal, useAction, useLoad, type FormValues } from '../components/ui'
import { useAuth } from '../auth'
import { CUSTOMER_FIELDS } from './Customers'

interface Detail {
  customer: Row
  displayName: string
  addresses: Row[]
  beneficiaries: Row[]
  documents: Row[]
  statusHistory: Row[]
  kycCases: Row[]
  policies: Row[]
  kycMissing: string[]
}

const TABS = ['Documents & KYC', 'Addresses', 'Beneficiaries', 'Policies', 'History', 'Duplicates'] as const

export default function CustomerDetail() {
  const { id } = useParams()
  const { hasRole } = useAuth()
  const d = useLoad(() => api.get<Detail>(`/customers/${id}`), [id])
  const dups = useLoad(() => api.get<Row[]>(`/customers/${id}/duplicates`), [id])
  const [tab, setTab] = useState<(typeof TABS)[number]>('Documents & KYC')
  const [editing, setEditing] = useState(false)
  const a = useAction()
  const base = `/customers/${id}`
  const act = (fn: () => Promise<unknown>, msg: string) => a.run(fn, msg).then((r) => { if (r !== undefined) d.reload(); return r })
  if (!d.data) return <Alert error={d.error} />
  const c = d.data.customer
  const initial = Object.fromEntries(CUSTOMER_FIELDS.map((f) => [f.name, c[f.name] == null ? '' : String(c[f.name])])) as FormValues

  return (
    <>
      <h2>{d.data.displayName} <span className="muted">{String(c.customerNo)}</span></h2>
      <Alert error={a.error} message={a.message} />
      <Card title="Profile" actions={<>
        {hasRole('CSR', 'UNDERWRITER') && <button className="secondary" onClick={() => setEditing(true)}>Edit</button>}
        <Link to={`/policies?customerId=${id}`}><button className="secondary">Policies</button></Link>
      </>}>
        <KeyValues data={c} keys={['customerType', 'nationalId', 'dateOfBirth', 'gender', 'email', 'phone', 'occupationCode', 'riskClass', 'segment', 'kycStatus', 'status', 'createdDt', 'createdBy']} />
        {d.data.kycMissing.length > 0 && <p className="muted">KYC missing: {d.data.kycMissing.join(', ')}</p>}
        {hasRole('CSR', 'MANAGER') && (
          <Form inline busy={a.busy} submitLabel="Change status"
            fields={[{ name: 'status', type: 'select', options: [{ value: 'A', label: 'A — Active' }, { value: 'I', label: 'I — Inactive' }, { value: 'B', label: 'B — Blocked' }, { value: 'D', label: 'D — Deceased/Closed' }], required: true }, { name: 'reason' }]}
            onSubmit={(p) => act(() => api.post(`${base}/status`, p), 'Status changed')} />
        )}
      </Card>
      <div className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? 'on' : ''} onClick={() => setTab(t)}>{t}</button>)}</div>

      {tab === 'Documents & KYC' && (
        <>
          <Card title="KYC" actions={<>
            {hasRole('CSR', 'UNDERWRITER') && <button className="secondary" onClick={() => act(() => api.post(`${base}/kyc/open`), 'KYC case opened')}>Open KYC case</button>}
            {hasRole('UNDERWRITER', 'MANAGER') && <>
              <button onClick={() => act(() => api.post(`${base}/kyc/decision`, { approve: true, note: 'Verified' }), 'KYC approved')}>Approve KYC</button>
              <button className="danger" onClick={() => act(() => api.post(`${base}/kyc/decision`, { approve: false, note: 'Rejected' }), 'KYC rejected')}>Reject KYC</button>
            </>}
          </>}>
            <p>Current status: <Badge value={c.kycStatus} /></p>
            <DataTable rows={d.data.kycCases} empty="No KYC cases" />
          </Card>
          <Card title="Documents">
            {hasRole('CSR', 'UNDERWRITER') && (
              <Form inline busy={a.busy} submitLabel="Add document"
                fields={[{ name: 'docType', type: 'select', options: ['NATIONAL_ID', 'PASSPORT', 'PROOF_ADDRESS', 'INCOME_PROOF', 'COMPANY_REG', 'OTHER'], required: true },
                  { name: 'docNumber' }, { name: 'fileName' }, { name: 'issueDt', type: 'date' }, { name: 'expiryDt', type: 'date' }]}
                onSubmit={(p) => act(() => api.post(`${base}/documents`, p), 'Document added')} />
            )}
            <DataTable rows={d.data.documents} columns={[{ key: 'docType', label: 'Type' }, { key: 'docNumber', label: 'Number' }, { key: 'fileName', label: 'File' },
              { key: 'expiryDt', label: 'Expiry' }, { key: 'status' }, { key: 'reviewedBy', label: 'Reviewed by' },
              { key: '_', label: '', render: (r) => hasRole('UNDERWRITER', 'MANAGER') && <span className="actions">
                <button className="secondary" onClick={() => act(() => api.post(`${base}/documents/${r.id}/review`, { status: 'VERIFIED', note: 'ok' }), 'Document verified')}>Verify</button>
                <button className="secondary" onClick={() => act(() => api.post(`${base}/documents/${r.id}/review`, { status: 'REJECTED', note: 'illegible' }), 'Document rejected')}>Reject</button>
              </span> }]} />
          </Card>
        </>
      )}
      {tab === 'Addresses' && (
        <Card title="Addresses">
          <Form inline busy={a.busy} submitLabel="Add address"
            fields={[{ name: 'addressType', type: 'select', options: ['HOME', 'MAIL', 'WORK', 'RISK'], required: true }, { name: 'line1', required: true }, { name: 'line2' },
              { name: 'city', required: true }, { name: 'state' }, { name: 'postalCode' }, { name: 'country' }, { name: 'primary', type: 'checkbox' }]}
            onSubmit={(p) => act(() => api.post(`${base}/addresses`, p), 'Address added')} />
          <DataTable rows={d.data.addresses} columns={[{ key: 'addressType', label: 'Type' }, { key: 'line1' }, { key: 'city' }, { key: 'state' }, { key: 'postalCode' }, { key: 'country' },
            { key: 'primaryFlag', label: 'Primary' }, { key: '_', label: '', render: (r) => <button className="secondary" onClick={() => act(() => api.del(`${base}/addresses/${r.id}`), 'Address removed')}>Remove</button> }]} />
        </Card>
      )}
      {tab === 'Beneficiaries' && (
        <Card title="Beneficiaries (shares must total 100%)">
          <Form inline busy={a.busy} submitLabel="Add beneficiary"
            fields={[{ name: 'fullName', required: true }, { name: 'relationship', type: 'select', options: ['SPOUSE', 'CHILD', 'PARENT', 'SIBLING', 'OTHER'] },
              { name: 'sharePct', type: 'number', required: true }, { name: 'dateOfBirth', type: 'date' }]}
            onSubmit={(p) => act(() => api.post(`${base}/beneficiaries`, p), 'Beneficiary added')} />
          <DataTable rows={d.data.beneficiaries} columns={[{ key: 'fullName', label: 'Name' }, { key: 'relationship' }, { key: 'sharePct', label: 'Share %' }, { key: 'dateOfBirth', label: 'DOB' },
            { key: '_', label: '', render: (r) => <button className="secondary" onClick={() => act(() => api.del(`${base}/beneficiaries/${r.id}`), 'Beneficiary removed')}>Remove</button> }]} />
        </Card>
      )}
      {tab === 'Policies' && <Card title="Policies"><DataTable rows={d.data.policies} /></Card>}
      {tab === 'History' && <Card title="Status history"><DataTable rows={d.data.statusHistory} /></Card>}
      {tab === 'Duplicates' && (
        <Card title="Potential duplicates">
          <DataTable rows={dups.data} empty="No likely duplicates" columns={dups.data?.[0] ? [...Object.keys(dups.data[0]).slice(0, 7).map((key) => ({ key })),
            { key: '_', label: '', render: (r: Row) => hasRole('MANAGER') && <button className="secondary" onClick={() => act(() => api.post('/customers/merge', { survivorId: Number(id), duplicateId: r.id ?? r.customer_id, reason: 'Duplicate record' }), 'Merged')}>Merge into this</button> }] : undefined} />
        </Card>
      )}
      {editing && (
        <Modal title="Edit customer" onClose={() => setEditing(false)}>
          <Form fields={CUSTOMER_FIELDS} initial={initial} busy={a.busy}
            onSubmit={(p) => act(() => api.put(base, p), 'Customer updated').then((r) => r !== undefined && setEditing(false))} />
        </Modal>
      )}
    </>
  )
}
