import { useAction, Alert, Card, Form } from '../components/ui'
import { useAuth } from '../auth'

export default function Login() {
  const { login } = useAuth()
  const a = useAction()
  return (
    <div className="login">
      <Card title="LegacyIns — Sign in">
        <Alert error={a.error} />
        <Form
          fields={[{ name: 'username', required: true }, { name: 'password', type: 'password', required: true }]}
          submitLabel="Sign in"
          busy={a.busy}
          onSubmit={(p) => a.run(() => login(String(p.username), String(p.password)))}
        />
        <p className="muted">Demo users: admin, csr1, uw1, adj1, fin1, mgr1 (see README).</p>
      </Card>
    </div>
  )
}
