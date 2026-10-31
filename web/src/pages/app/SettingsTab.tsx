import { useState } from 'preact/hooks'
import { api, type AppDetail } from '../../api'
import { useActions } from '../../actions'
import { Button, Card, Modal } from '../../components/ui'
import { navigate } from '../../hooks'

export function SettingsTab({ app, state, refresh }: { app: AppDetail; state: string; refresh: () => void }) {
  const { busy, run, modal } = useActions(refresh)
  const [destroying, setDestroying] = useState(false)
  const [confirm, setConfirm] = useState('')
  const base = `/api/apps/${app.name}`

  return (
    <div class="stack">
      <Card title="Process">
        <div class="setting">
          <div>
            <b>Restart</b>
            <p class="muted">Stops the active release and starts it again. Requests fail for a moment. To change the configuration with no downtime, edit the secrets instead.</p>
          </div>
          <Button icon="refresh" busy={busy === 'Restart'} disabled={app.active === null || busy !== null} onClick={() => run('Restart', () => api.post(`${base}/restart`), `${app.name} restarted`)}>
            Restart
          </Button>
        </div>
        <div class="setting">
          <div>
            <b>{state === 'running' ? 'Stop' : 'Start'}</b>
            <p class="muted">{state === 'running' ? 'Stops the app. It stays stopped, also after a reboot, until you start it.' : 'Starts the active release.'}</p>
          </div>
          {state === 'running' ? (
            <Button icon="stop" busy={busy === 'Stop'} disabled={busy !== null} onClick={() => run('Stop', () => api.post(`${base}/stop`), `${app.name} stopped`)}>
              Stop
            </Button>
          ) : (
            <Button icon="play" busy={busy === 'Start'} disabled={app.active === null || busy !== null} onClick={() => run('Start', () => api.post(`${base}/start`), `${app.name} started`)}>
              Start
            </Button>
          )}
        </div>
      </Card>

      <Card title="Deploy with git">
        <p class="muted">
          On the server run <code>unitship git init {app.name}</code>, then push to the remote it prints. The server builds the pushed commit and deploys it.
        </p>
      </Card>

      <Card title="Danger zone" class="danger">
        <div class="setting">
          <div>
            <b>Delete this app</b>
            <p class="muted">Stops it and deletes its releases, its data directory, its environment and its git repository. This cannot be undone.</p>
          </div>
          <Button kind="danger" icon="trash" onClick={() => setDestroying(true)}>
            Delete app
          </Button>
        </div>
      </Card>

      {destroying && (
        <Modal
          title={`Delete ${app.name}?`}
          onClose={() => setDestroying(false)}
          footer={
            <>
              <Button onClick={() => setDestroying(false)}>Cancel</Button>
              <Button
                kind="danger"
                busy={busy === 'Delete'}
                disabled={confirm !== app.name}
                onClick={async () => {
                  const ok = await run('Delete', () => api.delete(base, { confirm: app.name }), `${app.name} deleted`)
                  if (ok) navigate()
                }}
              >
                Delete app
              </Button>
            </>
          }
        >
          <p>
            Its releases and its data will be deleted. Type <b>{app.name}</b> to confirm.
          </p>
          <input class="block" value={confirm} onInput={(e) => setConfirm((e.target as HTMLInputElement).value)} placeholder={app.name} autoFocus spellcheck={false} />
        </Modal>
      )}
      {modal}
    </div>
  )
}
