import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, qs, type Page, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, Modal, Pager, useAction, useLoad, type Field } from '../components/ui'
import { useAuth } from '../auth'

export const CUSTOMER_FIELDS: Field[] = [
  { name: 'customerType', type: 'select', options: ['INDIVIDUAL', 'CORPORATE'], required: true },
  { name: 'firstName' }, { name: 'lastName' }, { name: 'companyName' },
  { name: 'nationalId', label: 'National ID / Reg. no.' }, { name: 'dateOfBirth', type: 'date' },
  { name: 'gender', type: 'select', options: ['M', 'F', 'X'] }, { name: 'email' }, { name: 'phone' },
  { name: 'occupationCode' }, { name: 'riskClass', type: 'select', options: ['STD', 'PREF', 'SUB', 'HIGH'] },
  { name: 'segment', type: 'select', options: ['RETAIL', 'SME', 'CORP', 'VIP'] },
]

export default function Customers() {
  const nav = useNavigate()
  const { hasRole } = useAuth()
  const [filter, setFilter] = useState<{ q?: string; status?: string; page: number }>({ page: 0 })
  const list = useLoad(() => api.get<Page<Row>>(`/customers${qs({ ...filter, size: 20 })}`), [filter])
  const [creating, setCreating] = useState(false)
  const a = useAction()
  return (
    <>
      <h2>Customers</h2>
      <Alert error={list.error} />
      <Card title="Search" actions={hasRole('CSR', 'UNDERWRITER') && <button onClick={() => setCreating(true)}>New customer</button>}>
        <Form inline submitLabel="Search"
          fields={[{ name: 'q', label: 'Name, number, national ID, email' }, { name: 'status', type: 'select', options: ['A', 'I', 'B', 'D'] }]}
          onSubmit={(p) => setFilter({ q: (p.q as string) ?? undefined, status: (p.status as string) ?? undefined, page: 0 })} />
      </Card>
      <Card title={`Results (${list.data?.totalElements ?? 0})`}>
        <DataTable rows={list.data?.content} onRowClick={(r) => nav(`/customers/${r.id}`)}
          columns={[{ key: 'customerNo', label: 'Customer no' }, { key: 'customerType', label: 'Type' },
            { key: 'name', label: 'Name', render: (r) => String(r.companyName ?? `${r.firstName ?? ''} ${r.lastName ?? ''}`) },
            { key: 'nationalId', label: 'National ID' }, { key: 'email' }, { key: 'segment' }, { key: 'riskClass', label: 'Risk' },
            { key: 'kycStatus', label: 'KYC status' }, { key: 'status' }]} />
        <Pager page={filter.page} totalPages={list.data?.totalPages ?? 0} onPage={(page) => setFilter({ ...filter, page })} />
      </Card>
      {creating && (
        <Modal title="New customer" onClose={() => setCreating(false)}>
          <Alert error={a.error} />
          <Form fields={CUSTOMER_FIELDS} initial={{ customerType: 'INDIVIDUAL', riskClass: 'STD', segment: 'RETAIL' }} busy={a.busy} submitLabel="Create"
            onSubmit={(p) => a.run(() => api.post<Row>('/customers', p)).then((c) => c && nav(`/customers/${c.id}`))} />
        </Modal>
      )}
    </>
  )
}
