import { useState } from 'preact/hooks'
import { api, type AppDetail } from '../../api'
import { Confirm, useActions } from '../../actions'
import { Button, Card, Empty, Icon, Pill } from '../../components/ui'
import { absolute, daysUntil } from '../../format'

const validDomain = (d: string) => /^[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?$/.test(d) && d.includes('.')

export function DomainsTab({ app, refresh }: { app: AppDetail; refresh: () => void }) {
  const { busy, run, modal } = useActions(refresh)
  const [domain, setDomain] = useState('')
  const [removing, setRemoving] = useState<string | null>(null)
  const base = `/api/apps/${app.name}`
  const name = domain.trim().toLowerCase()
  const exists = app.domains.some((d) => d.name === name)

  const add = async (e: Event) => {
    e.preventDefault()
    if (await run('Add domain', () => api.post(`${base}/domains`, { add: [name] }), `${name} added`)) setDomain('')
  }

  return (
    <div class="stack">
      <Card title="Domains" flush>
        {app.domains.length === 0 ? (
          <Empty icon="globe" title="No domain">
            Add a domain that points to this server to reach the app.
          </Empty>
        ) : (
          <table class="table">
            <thead>
              <tr>
                <th>Domain</th>
                <th>HTTPS</th>
                <th>Certificate expires</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {app.domains.map((d) => {
                const days = daysUntil(d.certificateExpires)
                return (
                  <tr key={d.name}>
                    <td>
                      <a class="domain strong" href={`${d.https ? 'https' : 'http'}://${d.name}`} target="_blank" rel="noreferrer">
                        {d.https && <Icon name="lock" size={12} />}
                        {d.name} <Icon name="external" size={12} />
                      </a>
                    </td>
                    <td>
                      <Pill tone={d.https ? 'green' : 'amber'}>{d.https ? 'on' : 'off'}</Pill>
                    </td>
                    <td title={d.certificateExpires ? absolute(d.certificateExpires) : ''}>
                      {d.certificateExpires && days !== null ? (
                        <>
                          {new Date(d.certificateExpires).toLocaleDateString()} <Pill tone={days < 7 ? 'red' : days < 30 ? 'amber' : 'gray'}>{days < 0 ? 'expired' : `${days} days`}</Pill>
                        </>
                      ) : (
                        <span class="muted">–</span>
                      )}
                    </td>
                    <td class="actions">
                      <Button size="sm" icon="lock" busy={busy === `Certificate ${d.name}`} disabled={busy !== null} onClick={() => run(`Certificate ${d.name}`, () => api.post(`${base}/tls`, { domains: [d.name] }), `Certificate of ${d.name} ready`)}>
                        {d.https ? 'Renew' : 'Get certificate'}
                      </Button>
                      <Button size="sm" kind="ghost" icon="trash" aria-label={`Remove ${d.name}`} onClick={() => setRemoving(d.name)} />
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </Card>

      <Card title="Add a domain">
        <form class="inline-form" onSubmit={add}>
          <label class="field grow">
            <span>Domain</span>
            <input value={domain} onInput={(e) => setDomain((e.target as HTMLInputElement).value)} placeholder="app.example.com" spellcheck={false} />
            <small class={name !== '' && (!validDomain(name) || exists) ? 'error' : 'muted'}>{exists ? 'The app has this domain.' : name !== '' && !validDomain(name) ? 'Not a valid domain.' : 'Point an A record of the domain to this server first.'}</small>
          </label>
          <Button kind="primary" type="submit" icon="plus" busy={busy === 'Add domain'} disabled={!validDomain(name) || exists || busy !== null}>
            Add
          </Button>
        </form>
        <p class="muted">
          “Get certificate” asks Let’s Encrypt for a certificate and turns HTTPS on. The domain must reach this server on port 80, and the host needs <code>lego</code>. Certificates are renewed every day when they have less than 30 days left.
        </p>
      </Card>

      {removing !== null && (
        <Confirm
          title={`Remove ${removing}?`}
          confirm="Remove"
          danger
          busy={busy === 'Remove domain'}
          onClose={() => setRemoving(null)}
          onConfirm={async () => {
            await run('Remove domain', () => api.post(`${base}/domains`, { remove: [removing] }), `${removing} removed`)
            setRemoving(null)
          }}
        >
          <p>
            <b>{removing}</b> will stop reaching {app.name}.
          </p>
        </Confirm>
      )}
      {modal}
    </div>
  )
}
