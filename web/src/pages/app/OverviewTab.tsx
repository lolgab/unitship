import { useState } from 'preact/hooks'
import { api, type AppDetail, type Point } from '../../api'
import { Confirm, useActions } from '../../actions'
import { Button, Card, Empty, Icon, Pill, Stat, StatePill } from '../../components/ui'
import { ago, bytes, cpuTime, milliseconds, number } from '../../format'
import { navigate, useApi, useNow } from '../../hooks'
import { currentRate } from '../../metrics'
import { EventRow } from './ActivityTab'

export function OverviewTab({ app, onDeploy, refresh }: { app: AppDetail; onDeploy: () => void; refresh: () => void }) {
  const now = useNow()
  const metrics = useApi<Point[]>(`/api/apps/${app.name}/metrics?minutes=15`, 15000)
  const [rollingBack, setRollingBack] = useState<number | null>(null)
  const { busy, run, modal } = useActions(refresh)
  const active = app.releases.find((r) => r.active)
  const rate = metrics.data ? currentRate(metrics.data) : null
  const last = metrics.data?.filter((p) => p.requests > 0).slice(-1)[0]

  return (
    <div class="overview">
      <div class="stats">
        <Card>
          <Stat label="Status" value={<StatePill state={active ? active.state : 'none'} />} sub={active ? `release #${active.id}, deployed ${ago(active.createdAt, now)}` : 'deploy an executable to start'} />
        </Card>
        <Card>
          <Stat label="Memory" value={bytes(active?.memoryBytes)} sub={active?.cpuNanos != null ? `${cpuTime(active.cpuNanos)} of CPU time` : 'the app and its processes'} />
        </Card>
        <Card>
          <Stat label="Traffic" value={rate === null ? '–' : `${number(rate)}/min`} sub="requests, last collection" />
        </Card>
        <Card>
          <Stat label="Latency" value={last ? milliseconds(last.avgMs) : '–'} sub={last ? `p95 ${milliseconds(last.p95Ms)}` : 'average of the last busy minute'} />
        </Card>
      </div>

      <div class="columns">
        <Card
          title="Releases"
          flush
          actions={
            <Button size="sm" icon="upload" onClick={onDeploy}>
              Deploy
            </Button>
          }
        >
          {app.releases.length === 0 ? (
            <Empty icon="upload" title="Nothing deployed yet">
              Upload an executable built with <code>snunit package</code>, or push with <code>git push</code>.
              <div class="empty-action">
                <Button kind="primary" icon="upload" onClick={onDeploy}>
                  Deploy
                </Button>
              </div>
            </Empty>
          ) : (
            <table class="table">
              <thead>
                <tr>
                  <th>Release</th>
                  <th>State</th>
                  <th>Created</th>
                  <th class="num">Size</th>
                  <th class="num">Memory</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {app.releases.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <span class="strong">#{r.id}</span> {r.active && <Pill tone="violet">active</Pill>} {r.previous && !r.active && <Pill tone="gray">previous</Pill>}
                    </td>
                    <td>{r.active || r.state === 'running' ? <StatePill state={r.state} /> : <span class="muted">idle</span>}</td>
                    <td title={r.createdAt}>{ago(r.createdAt, now)}</td>
                    <td class="num">{bytes(r.sizeBytes)}</td>
                    <td class="num">{bytes(r.memoryBytes)}</td>
                    <td class="actions">{!r.active && <Button size="sm" icon="rollback" onClick={() => setRollingBack(r.id)}>Roll back</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>

        <div class="stack">
          <Card title="Domains" flush actions={<Button size="sm" onClick={() => navigate('apps', app.name, 'domains')}>Manage</Button>}>
            {app.domains.length === 0 ? (
              <div class="card-note muted">No domain. Add one in “Domains & TLS”.</div>
            ) : (
              <ul class="list">
                {app.domains.map((d) => (
                  <li key={d.name}>
                    <a class="domain" href={`${d.https ? 'https' : 'http'}://${d.name}`} target="_blank" rel="noreferrer">
                      {d.https && <Icon name="lock" size={12} />}
                      {d.name}
                    </a>
                    <Pill tone={d.https ? 'green' : 'amber'}>{d.https ? 'HTTPS' : 'HTTP only'}</Pill>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="Recent activity" flush actions={<Button size="sm" onClick={() => navigate('apps', app.name, 'activity')}>All</Button>}>
            {app.events.length === 0 ? (
              <div class="card-note muted">Nothing yet.</div>
            ) : (
              <ul class="timeline compact">
                {[...app.events].reverse().slice(0, 5).map((e, i) => (
                  <EventRow key={i} event={e} now={now} />
                ))}
              </ul>
            )}
          </Card>
        </div>
      </div>

      {rollingBack !== null && (
        <Confirm
          title={`Roll back to release #${rollingBack}?`}
          confirm="Roll back"
          busy={busy === 'Roll back'}
          onClose={() => setRollingBack(null)}
          onConfirm={async () => {
            await run('Roll back', () => api.post(`/api/apps/${app.name}/rollback`, { release: rollingBack }), `Rolled back to #${rollingBack}`)
            setRollingBack(null)
          }}
        >
          <p>
            Release #{rollingBack} starts, and once it answers the edge switches to it with no downtime. Release #{active?.id} stays available to switch back.
          </p>
        </Confirm>
      )}
      {modal}
    </div>
  )
}
