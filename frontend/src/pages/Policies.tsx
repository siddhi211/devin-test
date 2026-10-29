import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, qs, type Page, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, Modal, Pager, useAction, useLoad, type Field } from '../components/ui'
import { useAuth } from '../auth'

export const POLICY_FIELDS = (products: Row[] | null): Field[] => [
  { name: 'customerId', type: 'number', required: true },
  { name: 'productId', type: 'select', required: true, options: (products ?? []).map((p) => ({ value: String(p.id), label: `${p.productCode} — ${p.productName}` })) },
  { name: 'sumInsured', type: 'number', required: true }, { name: 'termMonths', type: 'number', required: true },
  { name: 'paymentFrequency', type: 'select', options: ['ANNUAL', 'SEMI', 'QUARTERLY', 'MONTHLY'], required: true },
  { name: 'riskClass', type: 'select', options: ['STD', 'PREF', 'SUB', 'HIGH'] }, { name: 'occupationCode' },
  { name: 'medicalCodes', placeholder: 'comma separated' }, { name: 'startDt', type: 'date', required: true },
  { name: 'branchCd' }, { name: 'smoker', type: 'checkbox' },
]

const STATUSES = ['DRAFT', 'SUBMITTED', 'PENDING_UW', 'UW_APPROVED', 'ACTIVE', 'GRACE', 'LAPSED', 'DECLINED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED']

export default function Policies() {
  const nav = useNavigate()
  const { hasRole } = useAuth()
  const [params] = useSearchParams()
  const [filter, setFilter] = useState<Record<string, string | number | undefined>>({ page: 0, customerId: params.get('customerId') ?? undefined })
  const list = useLoad(() => api.get<Page<Row>>(`/policies${qs({ ...filter, size: 20 })}`), [filter])
  const products = useLoad(() => api.get<Row[]>('/products'))
  const [creating, setCreating] = useState(false)
  const a = useAction()
  return (
    <>
      <h2>Policies</h2>
      <Alert error={list.error} />
      <Card title="Search" actions={hasRole('CSR', 'UNDERWRITER') && <button onClick={() => setCreating(true)}>New policy</button>}>
        <Form inline submitLabel="Search" initial={{ customerId: String(filter.customerId ?? '') }}
          fields={[{ name: 'q', label: 'Policy no / customer' }, { name: 'status', type: 'select', options: STATUSES }, { name: 'customerId', type: 'number' }]}
          onSubmit={(p) => setFilter({ q: p.q as string, status: p.status as string, customerId: p.customerId as number, page: 0 })} />
      </Card>
      <Card title={`Results (${list.data?.totalElements ?? 0})`}>
        <DataTable rows={list.data?.content} onRowClick={(r) => nav(`/policies/${r.id}`)}
          columns={[{ key: 'policyNo', label: 'Policy no' }, { key: 'customerName', label: 'Customer' }, { key: 'productCode', label: 'Product' },
            { key: 'sumInsured', label: 'Sum insured' }, { key: 'annualPremium', label: 'Annual premium' }, { key: 'paymentFrequency', label: 'Frequency' },
            { key: 'startDt', label: 'Start' }, { key: 'endDt', label: 'End' }, { key: 'status' }]} />
        <Pager page={Number(filter.page)} totalPages={list.data?.totalPages ?? 0} onPage={(page) => setFilter({ ...filter, page })} />
      </Card>
      {creating && (
        <Modal title="New policy (draft)" onClose={() => setCreating(false)}>
          <Alert error={a.error} />
          <Form fields={POLICY_FIELDS(products.data)} busy={a.busy} submitLabel="Create draft"
            initial={{ paymentFrequency: 'ANNUAL', termMonths: '12', riskClass: 'STD', startDt: new Date().toISOString().slice(0, 10), customerId: String(filter.customerId ?? '') }}
            onSubmit={(p) => a.run(() => api.post<Row>('/policies', p)).then((r) => r && nav(`/policies/${r.id}`))} />
        </Modal>
      )}
    </>
  )
}
