import { useState } from 'preact/hooks'
import { api, type AppDetail } from '../../api'
import { Confirm, useActions } from '../../actions'
import { Button, Card, Empty, Icon, Modal, Pill } from '../../components/ui'

const validKey = (k: string) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(k)
const reserved = new Set(['PORT', 'BIND_ADDRESS', 'DATA_DIR', 'UNITSHIP_APP', 'UNITSHIP_RELEASE', 'UNITSHIP_HOME', 'UNITSHIP_BIN'])

export function SecretsTab({ app, refresh }: { app: AppDetail; refresh: () => void }) {
  const { busy, run, modal } = useActions(refresh)
  const [editing, setEditing] = useState<string | 'new' | null>(null)
  const [removing, setRemoving] = useState<string | null>(null)
  const base = `/api/apps/${app.name}/env`
  const active = app.active !== null

  return (
    <div class="stack">
      <Card
        title="Secrets and environment variables"
        flush
        actions={
          <Button size="sm" kind="primary" icon="plus" onClick={() => setEditing('new')}>
            Add
          </Button>
        }
      >
        <div class="card-note muted">
          The app reads these from its environment. Values are never shown again after you save them.{' '}
          {active ? 'Saving starts a new release with the change, with no downtime.' : 'They are used by the first release.'}
        </div>
        {app.env.length === 0 ? (
          <Empty icon="key" title="No variables">
            Add a database URL, an API key or any setting your app reads from the environment.
          </Empty>
        ) : (
          <table class="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Value</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {app.env.map((k) => (
                <tr key={k}>
                  <td>
                    <code class="strong">{k}</code> {k.startsWith('UNITSHIP_') && <Pill tone="blue">setting</Pill>}
                  </td>
                  <td class="muted">••••••••</td>
                  <td class="actions">
                    <Button size="sm" onClick={() => setEditing(k)}>
                      Update
                    </Button>
                    <Button size="sm" kind="ghost" icon="trash" aria-label={`Delete ${k}`} onClick={() => setRemoving(k)} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Provided by unitship">
        <div class="provided">
          {[
            ['PORT', 'the port to listen on'],
            ['BIND_ADDRESS', '127.0.0.1: listen on this address only'],
            ['DATA_DIR', 'a directory that is kept across releases'],
            ['UNITSHIP_APP', 'the name of the app'],
            ['UNITSHIP_RELEASE', 'the number of the release'],
          ].map(([k, d]) => (
            <div key={k}>
              <code>{k}</code>
              <span class="muted">{d}</span>
            </div>
          ))}
        </div>
      </Card>

      {editing !== null && (
        <SecretModal
          name={editing === 'new' ? '' : editing}
          existing={app.env}
          active={active}
          busy={busy === 'Save'}
          onClose={() => setEditing(null)}
          onSave={async (name, value, restart) => {
            const ok = await run('Save', () => api.put(base, { set: { [name]: value }, restart }), `${name} saved`)
            if (ok) setEditing(null)
          }}
        />
      )}
      {removing !== null && (
        <Confirm
          title={`Delete ${removing}?`}
          confirm="Delete"
          danger
          busy={busy === 'Delete'}
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            await run('Delete', () => api.put(base, { unset: [removing] }), `${removing} deleted`)
            setRemoving(null)
          }}
        >
          <p>
            The app will no longer see <code>{removing}</code>.{active && ' A new release starts without it.'}
          </p>
        </Confirm>
      )}
      {modal}
    </div>
  )
}

function SecretModal({ name, existing, active, busy, onClose, onSave }: { name: string; existing: string[]; active: boolean; busy: boolean; onClose: () => void; onSave: (name: string, value: string, restart: boolean) => void }) {
  const [key, setKey] = useState(name)
  const [value, setValue] = useState('')
  const [restart, setRestart] = useState(true)
  const [visible, setVisible] = useState(false)
  const isNew = name === ''
  const problem = key === '' ? null : !validKey(key) ? 'Letters, digits and “_”, not starting with a digit.' : reserved.has(key) ? `${key} is set by unitship.` : isNew && existing.includes(key) ? 'It exists: use Update.' : null
  const ok = key !== '' && problem === null
  const valueProps = { value, onInput: (e: Event) => setValue((e.target as HTMLInputElement).value), autoComplete: 'off', spellcheck: false, autoFocus: !isNew }

  return (
    <Modal
      title={isNew ? 'Add a variable' : `Update ${name}`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button kind="primary" busy={busy} disabled={!ok} onClick={() => onSave(key, value, restart)}>
            Save
          </Button>
        </>
      }
    >
      <form
        onSubmit={(e) => {
          e.preventDefault()
          if (ok) onSave(key, value, restart)
        }}
      >
        <label class="field">
          <span>Name</span>
          <input value={key} onInput={(e) => setKey((e.target as HTMLInputElement).value)} placeholder="DATABASE_URL" disabled={!isNew} spellcheck={false} autoFocus={isNew} />
          {problem && <small class="error">{problem}</small>}
        </label>
        <label class="field">
          <span>Value</span>
          <div class="input-group">
            {/* Preact 11 types `type` as a union per kind of input, so a 'text' | 'password' expression does not fit */}
            {visible ? <input type="text" {...valueProps} /> : <input type="password" {...valueProps} />}
            <button type="button" class="icon-btn" onClick={() => setVisible(!visible)} aria-label={visible ? 'Hide' : 'Show'}>
              <Icon name="eye" />
            </button>
          </div>
        </label>
        {active && (
          <label class="check block">
            <input type="checkbox" checked={restart} onChange={() => setRestart(!restart)} /> Start a new release with this change now
          </label>
        )}
      </form>
    </Modal>
  )
}
