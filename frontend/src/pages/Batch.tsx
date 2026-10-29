import { useState } from 'react'
import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, Modal, useAction, useLoad } from '../components/ui'

export default function Batch() {
  const jobs = useLoad(() => api.get<Row[]>('/batch/jobs'))
  const runs = useLoad(() => api.get<Row[]>('/batch/runs'))
  const [errors, setErrors] = useState<{ run: Row; rows: Row[] } | null>(null)
  const a = useAction()
  const refresh = () => { jobs.reload(); runs.reload() }
  return (
    <>
      <h2>Batch Jobs</h2>
      <Alert error={a.error ?? jobs.error} message={a.message} />
      <Card title="Run nightly cycle">
        <Form inline busy={a.busy} submitLabel="Run daily batch" fields={[{ name: 'asOf', type: 'date' }]}
          onSubmit={(p) => a.run(() => api.post(`/batch/run-daily${p.asOf ? `?asOf=${p.asOf}` : ''}`), 'Daily batch completed').then(refresh)} />
      </Card>
      <Card title="Job definitions">
        <DataTable rows={jobs.data} columns={jobs.data?.[0] ? [...Object.keys(jobs.data[0]).slice(0, 8).map((key) => ({ key })),
          { key: '_', label: '', render: (r: Row) => <span className="actions">
            <button disabled={a.busy} onClick={() => a.run(() => api.post<Row>(`/batch/jobs/${r.job_name}/run`), (x) => `${r.job_name}: ${x.status} (${x.items_processed ?? 0} processed)`).then(refresh)}>Run now</button>
            <button className="secondary" onClick={() => a.run(() => api.patch(`/batch/jobs/${r.job_name}`, { enabled: r.enabled !== 'Y' }), 'Job updated').then(refresh)}>{r.enabled === 'Y' ? 'Disable' : 'Enable'}</button>
          </span> }] : undefined} />
      </Card>
      <Card title="Run history" actions={<button className="secondary" onClick={refresh}>Refresh</button>}>
        <DataTable rows={runs.data} onRowClick={(r) => api.get<Row[]>(`/batch/runs/${r.run_id}/errors`).then((rows) => setErrors({ run: r, rows }))} />
      </Card>
      {errors && (
        <Modal title={`Errors for run ${errors.run.run_id} (${errors.run.job_name})`} onClose={() => setErrors(null)}>
          <DataTable rows={errors.rows} empty="No item errors" />
        </Modal>
      )}
    </>
  )
}
