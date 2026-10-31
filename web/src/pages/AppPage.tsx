import { useState } from 'preact/hooks'
import { api, ApiError, type AppDetail } from '../api'
import { Confirm, useActions } from '../actions'
import { Button, Empty, ErrorNote, Icon, Pill, Spinner, StatePill, Tabs } from '../components/ui'
import { navigate, useApi } from '../hooks'
import { useStatus } from '../status'
import { ActivityTab } from './app/ActivityTab'
import { DeployModal } from './app/DeployModal'
import { DomainsTab } from './app/DomainsTab'
import { MonitoringTab } from './app/MonitoringTab'
import { OverviewTab } from './app/OverviewTab'
import { SecretsTab } from './app/SecretsTab'
import { SettingsTab } from './app/SettingsTab'

export function AppPage({ name, tab }: { name: string; tab: string }) {
  const detail = useApi<AppDetail>(`/api/apps/${encodeURIComponent(name)}`, 5000)
  const status = useStatus()
  const [deploying, setDeploying] = useState(false)
  const [stopping, setStopping] = useState(false)

  const refresh = () => {
    detail.reload()
    status.reload()
  }
  const { busy, run, modal } = useActions(refresh)

  if (detail.error && !detail.data) {
    return (
      <div class="page">
        {detail.error instanceof ApiError && detail.error.status === 404 ? (
          <Empty icon="alert" title="App not found">
            There is no app named <code>{name}</code>. <a href="#/">Back to the dashboard</a>
          </Empty>
        ) : (
          <ErrorNote error={detail.error} retry={detail.reload} />
        )}
      </div>
    )
  }
  if (!detail.data) {
    return (
      <div class="page-loading">
        <Spinner size={28} />
      </div>
    )
  }

  const app = detail.data
  const summary = status.data?.apps.find((a) => a.name === name)
  const state = summary?.state ?? (app.active === null ? 'none' : app.releases.find((r) => r.active)?.state ?? 'none')
  const url = app.domains[0] ? `${app.domains[0].https ? 'https' : 'http'}://${app.domains[0].name}` : null
  const base = `/api/apps/${encodeURIComponent(name)}`

  const tabs = [
    { id: 'overview', label: 'Overview', icon: 'grid' },
    { id: 'monitoring', label: 'Monitoring', icon: 'activity' },
    { id: 'secrets', label: 'Secrets', icon: 'key', badge: app.env.length || undefined },
    { id: 'domains', label: 'Domains & TLS', icon: 'globe', badge: app.domains.length || undefined },
    { id: 'activity', label: 'Activity', icon: 'box' },
    { id: 'settings', label: 'Settings', icon: 'sliders' },
  ]
  const active = tabs.some((t) => t.id === tab) ? tab : 'overview'

  return (
    <div class="page">
      <div class="crumbs">
        <a href="#/">Apps</a>
        <Icon name="chevron" size={12} />
        <span>{name}</span>
      </div>
      <header class="page-header">
        <div>
          <h1 class="with-pill">
            {name} <StatePill state={state} />
          </h1>
          <div class="header-meta">
            {url ? (
              <a href={url} target="_blank" rel="noreferrer" class="domain">
                {app.domains[0]?.https && <Icon name="lock" size={12} />}
                {url.replace(/^https?:\/\//, '')} <Icon name="external" size={12} />
              </a>
            ) : (
              <span class="muted">No domain yet</span>
            )}
            {app.active !== null && <Pill tone="violet">release #{app.active}</Pill>}
          </div>
        </div>
        <div class="header-actions">
          {app.active !== null &&
            (state === 'running' ? (
              <>
                <Button icon="refresh" busy={busy === 'Restart'} disabled={busy !== null} onClick={() => run('Restart', () => api.post(`${base}/restart`), `${name} restarted`)}>
                  Restart
                </Button>
                <Button icon="stop" disabled={busy !== null} onClick={() => setStopping(true)}>
                  Stop
                </Button>
              </>
            ) : (
              <Button icon="play" busy={busy === 'Start'} disabled={busy !== null} onClick={() => run('Start', () => api.post(`${base}/start`), `${name} started`)}>
                Start
              </Button>
            ))}
          <Button kind="primary" icon="upload" onClick={() => setDeploying(true)}>
            Deploy
          </Button>
        </div>
      </header>

      <Tabs tabs={tabs} active={active} onSelect={(id) => navigate('apps', name, id)} />

      <div class="tab-panel">
        {active === 'overview' && <OverviewTab app={app} onDeploy={() => setDeploying(true)} refresh={refresh} />}
        {active === 'monitoring' && <MonitoringTab app={app} />}
        {active === 'secrets' && <SecretsTab app={app} refresh={refresh} />}
        {active === 'domains' && <DomainsTab app={app} refresh={refresh} />}
        {active === 'activity' && <ActivityTab app={app} />}
        {active === 'settings' && <SettingsTab app={app} state={state} refresh={refresh} />}
      </div>

      {deploying && <DeployModal app={name} onClose={() => setDeploying(false)} onDone={refresh} />}
      {stopping && (
        <Confirm
          title={`Stop ${name}?`}
          confirm="Stop"
          danger
          busy={busy === 'Stop'}
          onClose={() => setStopping(false)}
          onConfirm={async () => {
            await run('Stop', () => api.post(`${base}/stop`), `${name} stopped`)
            setStopping(false)
          }}
        >
          <p>
            Requests to <b>{name}</b> will fail until you start it again.
          </p>
        </Confirm>
      )}
      {modal}
    </div>
  )
}
