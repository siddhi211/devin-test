import { api, type Row } from '../api/client'
import { Alert, Card, DataTable, Form, useAction, useLoad } from '../components/ui'

export default function Admin() {
  const users = useLoad(() => api.get<Row[]>('/admin/users'))
  const audit = useLoad(() => api.get<Row[]>('/admin/audit'))
  const a = useAction()
  return (
    <>
      <h2>Users &amp; Audit</h2>
      <Alert error={a.error ?? users.error} message={a.message} />
      <Card title="Create user">
        <Form busy={a.busy} submitLabel="Create user"
          fields={[{ name: 'username', required: true }, { name: 'password', type: 'password', required: true }, { name: 'fullName', required: true }, { name: 'email' },
            { name: 'role', type: 'select', options: ['ADMIN', 'CSR', 'UNDERWRITER', 'ADJUSTER', 'FINANCE', 'MANAGER'], required: true }, { name: 'branchCd' }]}
          onSubmit={(p) => a.run(() => api.post('/admin/users', p), 'User created').then(users.reload)} />
      </Card>
      <Card title="Users"><DataTable rows={users.data} /></Card>
      <Card title="Audit trail"><DataTable rows={audit.data} /></Card>
    </>
  )
}
