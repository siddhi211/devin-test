import { useState } from 'react'
import { api, qs, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, useAction, useLoad, type FormValues } from '../components/ui'

interface ReportDef { code: string; name: string; description: string; params: string[] }

export default function Reports() {
  const catalogue = useLoad(() => api.get<ReportDef[]>('/reports'))
  const history = useLoad(() => api.get<Row[]>('/reports-history'))
  const filters = useLoad(() => api.get<Row[]>('/report-filters'))
  const [report, setReport] = useState<ReportDef | null>(null)
  const [params, setParams] = useState<Record<string, string>>({})
  const [result, setResult] = useState<Row | null>(null)
  const a = useAction()
  const run = (def: ReportDef, p: Record<string, string>) => {
    setParams(p)
    a.run(() => api.get<Row>(`/reports/${def.code}${qs(p)}`)).then((r) => { if (r) { setResult(r); history.reload() } })
  }
  const paramFields = (def: ReportDef) => def.params.map((n) => ({ name: n, type: /dt|date|from|to/i.test(n) ? 'date' as const : 'text' as const }))
  const clean = (p: Record<string, unknown>) => Object.fromEntries(Object.entries(p).filter(([, v]) => v !== null && v !== '').map(([k, v]) => [k, String(v)]))

  return (
    <>
      <h2>Reports</h2>
      <Alert error={a.error ?? catalogue.error} message={a.message} />
      <div className="grid2">
        <Card title="Report catalogue" actions={<button className="secondary" onClick={() => a.run(() => api.post('/reports-refresh'), 'Summary tables refreshed')}>Refresh summaries</button>}>
          <DataTable rows={catalogue.data as unknown as Row[]} onRowClick={(r) => { setReport(r as unknown as ReportDef); setResult(null) }}
            columns={[{ key: 'code' }, { key: 'name' }, { key: 'description' }]} />
        </Card>
        {report && (
          <Card title={report.name}>
            <p className="muted">{report.description}</p>
            <Form key={report.code} fields={paramFields(report)} submitLabel="Run report" busy={a.busy} initial={params as FormValues}
              onSubmit={(p) => run(report, clean(p))} />
            <div className="actions" style={{ marginTop: 10 }}>
              <button className="secondary" onClick={() => api.download(`/reports/${report.code}${qs({ ...params, format: 'csv' })}`, `${report.code.toLowerCase()}.csv`)}>Download CSV</button>
              <button className="secondary" onClick={() => {
                const name = window.prompt('Filter name')
                if (name) a.run(() => api.post('/report-filters', { reportCode: report.code, filterName: name, params: JSON.stringify(params) }), 'Filter saved').then(filters.reload)
              }}>Save filter</button>
            </div>
            <h4>Saved filters</h4>
            <DataTable rows={(filters.data ?? []).filter((f) => f.report_code === report.code)} empty="None"
              columns={[{ key: 'filter_name', label: 'Name' }, { key: 'params' },
                { key: '_', label: '', render: (f) => <span className="actions">
                  <button className="secondary" onClick={() => run(report, JSON.parse(String(f.params || '{}')))}>Run</button>
                  <button className="secondary" onClick={() => a.run(() => api.del(`/report-filters/${f.filter_id}`), 'Deleted').then(filters.reload)}>Delete</button>
                </span> }]} />
          </Card>
        )}
      </div>
      {result && (
        <Card title={`${String(result.name)} — ${String(result.rowCount)} rows in ${String(result.durationMs)} ms`}>
          <DataTable rows={result.rows as Row[]} columns={(result.columns as string[]).map((key) => ({ key }))} />
        </Card>
      )}
      <Card title="Run history"><DataTable rows={history.data} /></Card>
    </>
  )
}
