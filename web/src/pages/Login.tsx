import { useState } from 'preact/hooks'
import { api } from '../api'
import { Button, Icon } from '../components/ui'

export function Login({ configured, onLoggedIn }: { configured: boolean; onLoggedIn: () => void }) {
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: Event) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.login(password)
      onLoggedIn()
    } catch (err) {
      setError((err as Error).message)
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div class="login">
      <form class="login-card" onSubmit={submit}>
        <div class="brand brand-large">
          <span class="brand-mark">
            <Icon name="ship" size={22} />
          </span>
          unitship
        </div>
        <p class="muted">Sign in to manage your apps.</p>
        {!configured && (
          <div class="note note-amber">
            <Icon name="alert" />
            <div>
              No password is set yet. On the server run
              <pre class="inline-code">unitship exec panel set-password</pre>
            </div>
          </div>
        )}
        <label class="field">
          <span>Password</span>
          <input type="password" value={password} onInput={(e) => setPassword((e.target as HTMLInputElement).value)} autoComplete="current-password" autoFocus required />
        </label>
        {error && (
          <div class="note note-red" role="alert">
            <Icon name="alert" />
            <div>{error}</div>
          </div>
        )}
        <Button kind="primary" type="submit" busy={busy} disabled={!configured || password === ''} class="block">
          Sign in
        </Button>
      </form>
    </div>
  )
}
