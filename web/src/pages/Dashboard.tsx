import { useState } from 'preact/hooks'
import { api, type AppSummary, type Point } from '../api'
import { Sparkline } from '../components/Chart'
import { Button, Card, Empty, ErrorNote, Icon, Modal, Pill, Progress, Spinner, Stat, StatePill, useToast } from '../components/ui'
import { bytes, duration, number } from '../format'
import { navigate, useApi, useNow } from '../hooks'
import { bucketize, currentRate } from '../metrics'
import { useStatus } from '../status'

export function Dashboard() {
  const status = useStatus()
  const [creating, setCreating] = useState(false)

  if (status.loading) return <div class="page-loading"><Spinner size={28} /></div>
  if (status.error && !status.data) return <div class="page"><ErrorNote error={status.error} retry={status.reload} /></div>
  const s = status.data!
  const host = s.host
  const memoryUsed = host.memoryTotalBytes !== null && host.memoryAvailableBytes !== null ? host.memoryTotalBytes - host.memoryAvailableBytes : null
  const diskUsed = host.diskTotalBytes !== null && host.diskAvailableBytes !== null ? host.diskTotalBytes - host.diskAvailableBytes : null
  const appMemory = s.apps.reduce((a, x) => a + (x.memoryBytes ?? 0), 0)

  return (
    <div class="page">
      <header class="page-header">
        <div>
          <h1>Dashboard</h1>
          <p class="muted">
            {s.apps.length} {s.apps.length === 1 ? 'app' : 'apps'} · {s.apps.filter((a) => a.state === 'running').length} running
          </p>
        </div>
        <Button kind="primary" icon="plus" onClick={() => setCreating(true)}>
          New app
        </Button>
      </header>

      <div class="stats">
        <Card>
          <Stat
            label="Edge"
            value={
              <Pill tone={s.edge.state === 'running' ? 'green' : 'red'} dot>
                {s.edge.state}
              </Pill>
            }
            sub={`listening on :${s.edge.httpPort} and :${s.edge.httpsPort}`}
          >
            {s.edge.status && (
              <div class="stat-meta">
                <span>
                  <b>{number(s.edge.status.requests.total)}</b> requests
                </span>
                <span>
                  <b>{s.edge.status.connections.active}</b> active connections
                </span>
              </div>
            )}
          </Stat>
        </Card>
        <Card>
          <Stat label="Memory" value={memoryUsed === null ? bytes(appMemory) : `${bytes(memoryUsed)} / ${bytes(host.memoryTotalBytes)}`} sub={memoryUsed === null ? 'used by the apps' : `${bytes(appMemory)} used by the apps`}>
            {memoryUsed !== null && host.memoryTotalBytes ? <Progress value={(memoryUsed / host.memoryTotalBytes) * 100} /> : null}
          </Stat>
        </Card>
        <Card>
          <Stat label="Disk" value={diskUsed === null ? '–' : `${bytes(diskUsed)} / ${bytes(host.diskTotalBytes)}`} sub={`${bytes(host.diskAvailableBytes)} free`}>
            {diskUsed !== null && host.diskTotalBytes ? <Progress value={(diskUsed / host.diskTotalBytes) * 100} /> : null}
          </Stat>
        </Card>
        <Card>
          <Stat label="Load" value={host.load ? host.load.map((l) => l.toFixed(2)).join('  ') : '–'} sub={host.uptimeSeconds !== null ? `up ${duration(host.uptimeSeconds)}` : 'load average 1, 5, 15 min'} />
        </Card>
      </div>

      <Card title="Apps" flush>
        {s.apps.length === 0 ? (
          <Empty icon="ship" title="No apps yet">
            Create an app, then deploy an executable built with <code>snunit package</code> or push with git.
            <div class="empty-action">
              <Button kind="primary" icon="plus" onClick={() => setCreating(true)}>
                New app
              </Button>
            </div>
          </Empty>
        ) : (
          <table class="table table-click">
            <thead>
              <tr>
                <th>Name</th>
                <th>Status</th>
                <th>Domains</th>
                <th>Release</th>
                <th class="num">Memory</th>
                <th>Traffic, last hour</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {s.apps.map((a) => (
                <AppRow key={a.name} app={a} />
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {creating && <NewApp onClose={() => setCreating(false)} />}
    </div>
  )
}

function AppRow({ app }: { app: AppSummary }) {
  const now = useNow(30000)
  const metrics = useApi<Point[]>(`/api/apps/${app.name}/metrics?minutes=60`, 30000)
  const buckets = metrics.data ? bucketize(metrics.data, now, 60) : []
  const rate = metrics.data ? currentRate(metrics.data) : null
  return (
    <tr onClick={() => navigate('apps', app.name)} tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && navigate('apps', app.name)}>
      <td>
        <a class="strong" href={`#/apps/${app.name}`} onClick={(e) => e.stopPropagation()}>
          {app.name}
        </a>
      </td>
      <td>
        <StatePill state={app.state} />
      </td>
      <td>
        {app.domains.length === 0 ? (
          <span class="muted">–</span>
        ) : (
          <div class="domains">
            {app.domains.slice(0, 2).map((d) => (
              <a key={d} class="domain" href={`${app.httpsDomains.includes(d) ? 'https' : 'http'}://${d}`} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>
                {app.httpsDomains.includes(d) && <Icon name="lock" size={12} />}
                {d}
              </a>
            ))}
            {app.domains.length > 2 && <span class="muted">+{app.domains.length - 2}</span>}
          </div>
        )}
      </td>
      <td>{app.release !== null ? `#${app.release}` : <span class="muted">–</span>}</td>
      <td class="num">{bytes(app.memoryBytes)}</td>
      <td>
        <div class="traffic">
          <Sparkline values={buckets.map((b) => b.rate)} />
          <span class="muted">{rate === null ? '–' : `${number(rate)}/min`}</span>
        </div>
      </td>
      <td class="chevron">
        <Icon name="chevron" />
      </td>
    </tr>
  )
}

function NewApp({ onClose }: { onClose: () => void }) {
  const toast = useToast()
  const status = useStatus()
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const valid = /^[a-z]([a-z0-9-]{0,30}[a-z0-9])?$/.test(name)
  const taken = status.data?.apps.some((a) => a.name === name) ?? false

  const create = async (e: Event) => {
    e.preventDefault()
    setBusy(true)
    try {
      await api.post('/api/apps', { name })
      toast(`Created ${name}`)
      status.reload()
      onClose()
      navigate('apps', name)
    } catch (err) {
      toast((err as Error).message, 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title="New app"
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button kind="primary" busy={busy} disabled={!valid || taken} onClick={create}>
            Create app
          </Button>
        </>
      }
    >
      <form onSubmit={create}>
        <label class="field">
          <span>Name</span>
          <input value={name} onInput={(e) => setName((e.target as HTMLInputElement).value.toLowerCase())} placeholder="my-app" autoFocus spellcheck={false} />
          <small class={name !== '' && (!valid || taken) ? 'error' : 'muted'}>{taken ? 'An app with this name exists.' : name !== '' && !valid ? 'Lowercase letters, digits and “-”, starting with a letter.' : 'Used in the domain and the name of the service.'}</small>
        </label>
      </form>
    </Modal>
  )
}
