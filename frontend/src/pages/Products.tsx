import { useState } from 'react'
import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, KeyValues, Modal, money, useAction, useLoad, type Field } from '../components/ui'
import { Sections } from '../components/Sections'
import { useAuth } from '../auth'

export const QUOTE_FIELDS = (products: Row[] | null): Field[] => [
  { name: 'productId', type: 'select', required: true, options: (products ?? []).map((p) => ({ value: String(p.id), label: `${p.productCode} — ${p.productName}` })) },
  { name: 'customerId', type: 'number', label: 'Customer ID (or DOB)' }, { name: 'dateOfBirth', type: 'date' },
  { name: 'sumInsured', type: 'number', required: true }, { name: 'termMonths', type: 'number', required: true },
  { name: 'paymentFrequency', type: 'select', options: ['ANNUAL', 'SEMI', 'QUARTERLY', 'MONTHLY'], required: true },
  { name: 'riskClass', type: 'select', options: ['STD', 'PREF', 'SUB', 'HIGH'] }, { name: 'occupationCode' },
  { name: 'startDt', type: 'date' }, { name: 'smoker', type: 'checkbox' },
]

const PRODUCT_FIELDS: Field[] = [
  { name: 'productCode', required: true }, { name: 'productName', required: true },
  { name: 'lineOfBusiness', type: 'select', options: ['LIFE', 'HEALTH', 'MOTOR', 'HOME'], required: true }, { name: 'description' },
  { name: 'baseRate', type: 'number', required: true }, { name: 'minAge', type: 'number' }, { name: 'maxAge', type: 'number' },
  { name: 'minSumInsured', type: 'number' }, { name: 'maxSumInsured', type: 'number' }, { name: 'minTermMonths', type: 'number' }, { name: 'maxTermMonths', type: 'number' },
]

export default function Products() {
  const { hasRole } = useAuth()
  const products = useLoad(() => api.get<Row[]>('/products'))
  const uw = useLoad(() => api.get<Row>('/reference/underwriting'))
  const [selected, setSelected] = useState<Row | null>(null)
  const [quote, setQuote] = useState<Row | null>(null)
  const [creating, setCreating] = useState(false)
  const a = useAction()
  return (
    <>
      <h2>Products, Rating &amp; Quotes</h2>
      <Alert error={a.error ?? products.error} message={a.message} />
      <Card title="Product catalogue" actions={hasRole() && <button onClick={() => setCreating(true)}>New product</button>}>
        <DataTable rows={products.data} onRowClick={(p) => api.get<Row>(`/products/${p.id}`).then(setSelected)}
          columns={[{ key: 'productCode', label: 'Code' }, { key: 'productName', label: 'Name' }, { key: 'lineOfBusiness', label: 'LOB' },
            { key: 'baseRate', label: 'Base rate' }, { key: 'minAge', label: 'Min age' }, { key: 'maxAge', label: 'Max age' },
            { key: 'minSumInsured', label: 'Min SI' }, { key: 'maxSumInsured', label: 'Max SI' }, { key: 'versionNo', label: 'Ver' }, { key: 'status' }]} />
      </Card>
      <div className="grid2">
        <Card title="Premium quote">
          <Form fields={QUOTE_FIELDS(products.data)} busy={a.busy} submitLabel="Calculate premium"
            initial={{ paymentFrequency: 'ANNUAL', termMonths: '12', riskClass: 'STD' }}
            onSubmit={(p) => a.run(() => api.post<Row>('/quotes', p)).then((q) => q && setQuote(q))} />
        </Card>
        <Card title="Quote result">
          {quote ? (
            <>
              <KeyValues data={quote} keys={['productCode', 'age', 'ratePer1000', 'basePremium', 'annualPremium', 'installmentPremium', 'installmentsPerYear', 'paymentFrequency']} />
              <h4>Rating trace</h4>
              <pre>{(quote.trace as string[]).join('\n')}</pre>
              <p className="muted">Annual premium {money(quote.annualPremium)}</p>
            </>
          ) : <p className="muted">Enter details to calculate a premium.</p>}
        </Card>
      </div>
      {uw.data && <><h3>Underwriting reference</h3><Sections data={uw.data} /></>}
      {selected && (
        <Modal title={`Product ${String((selected.product as Row | undefined)?.productCode ?? selected.productCode ?? '')}`} onClose={() => setSelected(null)}>
          <Sections data={selected} />
          {hasRole() && (selected.product as Row | undefined)?.id !== undefined && (
            <Card title="Change status">
              <Form inline submitLabel="Update" fields={[{ name: 'status', type: 'select', options: ['A', 'I', 'S'], required: true }]}
                onSubmit={(p) => a.run(() => api.post(`/products/${(selected.product as Row).id}/status`, p), 'Product status updated').then(products.reload)} />
            </Card>
          )}
        </Modal>
      )}
      {creating && (
        <Modal title="New product" onClose={() => setCreating(false)}>
          <Alert error={a.error} />
          <Form fields={PRODUCT_FIELDS} busy={a.busy} submitLabel="Create"
            onSubmit={(p) => a.run(() => api.post('/products', p), 'Product created').then((r) => { if (r) { setCreating(false); products.reload() } })} />
        </Modal>
      )}
    </>
  )
}
