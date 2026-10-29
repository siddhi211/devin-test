import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, Modal, useAction, useLoad } from '../components/ui'
import { Sections } from '../components/Sections'

const subjectLink = (r: Row) => {
  const id = r.subject_id
  switch (r.subject_type) {
    case 'CLAIM': return `/claims/${id}`
    case 'UW_REFERRAL': return `/policies/${id}`
    default: return null
  }
}

export default function Workflow() {
  const inbox = useLoad(() => api.get<Row[]>('/workflow/inbox'))
  const instances = useLoad(() => api.get<Row[]>('/workflow/instances'))
  const delegations = useLoad(() => api.get<Row[]>('/workflow/delegations'))
  const [task, setTask] = useState<Row | null>(null)
  const [instance, setInstance] = useState<Row | null>(null)
  const a = useAction()
  const refresh = () => { inbox.reload(); instances.reload() }

  const act = (path: string, body: unknown, msg: string) =>
    a.run(() => api.post(path, body), msg).then((r) => { if (r) { setTask(null); refresh() } })

  return (
    <>
      <h2>Workflow &amp; Approvals</h2>
      <Alert error={a.error ?? inbox.error} message={a.message} />
      <Card title={`Inbox (${inbox.data?.length ?? 0})`} actions={<button className="secondary" onClick={refresh}>Refresh</button>}>
        <DataTable rows={inbox.data} onRowClick={setTask}
          columns={[{ key: 'task_id', label: '#' }, { key: 'def_code', label: 'Workflow' }, { key: 'step_name', label: 'Step' },
            { key: 'role_code', label: 'Role' }, { key: 'subject_type' },
            { key: 'subject_ref', label: 'Reference', render: (r) => { const l = subjectLink(r); return l ? <Link to={l} onClick={(e) => e.stopPropagation()}>{String(r.subject_ref ?? r.subject_id)}</Link> : String(r.subject_ref ?? r.subject_id) } },
            { key: 'amount' }, { key: 'requested_by' }, { key: 'assigned_user', label: 'Claimed by' }, { key: 'status' },
            { key: 'sla_due_dt', label: 'SLA due' }, { key: 'sla_breached', label: 'Breached' }]} />
      </Card>
      <Card title="Workflow instances">
        <DataTable rows={instances.data} onRowClick={(r) => api.get<Row>(`/workflow/instances/${r.instance_id}`).then(setInstance)} />
      </Card>
      <Card title="Delegations">
        <Form inline busy={a.busy} submitLabel="Delegate"
          fields={[{ name: 'toUsername', required: true }, { name: 'startDt', type: 'date', required: true }, { name: 'endDt', type: 'date', required: true }]}
          onSubmit={(p) => a.run(() => api.post('/workflow/delegations', p), 'Delegation created').then(delegations.reload)} />
        <DataTable rows={delegations.data} columns={delegations.data?.[0] ? [...Object.keys(delegations.data[0]).map((key) => ({ key })),
          { key: '_', label: '', render: (r: Row) => <button className="secondary" onClick={() => a.run(() => api.del(`/workflow/delegations/${r.delegation_id}`), 'Removed').then(delegations.reload)}>Remove</button> }] : undefined} />
      </Card>
      {task && (
        <Modal title={`${task.step_name} — ${task.subject_ref ?? task.subject_id}`} onClose={() => setTask(null)}>
          <Alert error={a.error} />
          <Sections data={task} />
          <div className="actions" style={{ marginBottom: 12 }}>
            <button className="secondary" onClick={() => act(`/workflow/tasks/${task.task_id}/claim`, {}, 'Task claimed')}>Claim</button>
            <button className="secondary" onClick={() => act(`/workflow/tasks/${task.task_id}/release`, {}, 'Task released')}>Release</button>
          </div>
          <Form busy={a.busy} submitLabel="Submit decision"
            fields={[{ name: 'decision', type: 'select', options: ['APPROVE', 'REJECT'], required: true }, { name: 'note', type: 'textarea', label: 'Note (required to reject)' }]}
            onSubmit={(p) => act(`/workflow/tasks/${task.task_id}/decide`, p, `Decision ${p.decision} recorded`)} />
        </Modal>
      )}
      {instance && <Modal title="Workflow instance" onClose={() => setInstance(null)}><Sections data={instance} /></Modal>}
    </>
  )
}
