import { useMemo } from 'preact/hooks'
import type { HostPoint } from '../api'
import { Chart, Legend, niceBytes } from '../components/Chart'
import { LogViewer } from '../components/LogViewer'
import { Card, ErrorNote, Pill, Progress, Spinner, Stat } from '../components/ui'
import { bytes, duration, number } from '../format'
import { useApi, useNow, useStored } from '../hooks'
import { bucketizeHost } from '../metrics'
import { useStatus } from '../status'
import { RangePicker } from './app/MonitoringTab'

export function HostPage() {
  const status = useStatus()
  const [minutes, setMinutes] = useStored<number>('range', 60)
  const now = useNow(30000)
  const metrics = useApi<HostPoint[]>(`/api/host/metrics?minutes=${minutes}`, 30000)
  const buckets = useMemo(() => (metrics.data ? bucketizeHost(metrics.data, now, minutes) : []), [metrics.data, now, minutes])

  if (status.loading) return <div class="page-loading"><Spinner size={28} /></div>
  if (!status.data) return <div class="page">{status.error && <ErrorNote error={status.error} retry={status.reload} />}</div>
  const { host, edge, apps } = status.data
  const memoryUsed = host.memoryTotalBytes !== null && host.memoryAvailableBytes !== null ? host.memoryTotalBytes - host.memoryAvailableBytes : null
  const diskUsed = host.diskTotalBytes !== null && host.diskAvailableBytes !== null ? host.diskTotalBytes - host.diskAvailableBytes : null
  const times = buckets.map((b) => b.t)
  const connections = edge.status?.connections

  return (
    <div class="page">
      <header class="page-header">
        <div>
          <h1>Host</h1>
          <p class="muted">The machine, and the edge that routes requests to the apps.</p>
        </div>
      </header>

      <div class="stats">
        <Card>
          <Stat label="Memory" value={memoryUsed === null ? '–' : `${bytes(memoryUsed)} / ${bytes(host.memoryTotalBytes)}`} sub={`${bytes(apps.reduce((a, x) => a + (x.memoryBytes ?? 0), 0))} used by the apps`}>
            {memoryUsed !== null && host.memoryTotalBytes ? <Progress value={(memoryUsed / host.memoryTotalBytes) * 100} /> : null}
          </Stat>
        </Card>
        <Card>
          <Stat label="Disk" value={diskUsed === null ? '–' : `${bytes(diskUsed)} / ${bytes(host.diskTotalBytes)}`} sub={`${bytes(host.diskAvailableBytes)} free`}>
            {diskUsed !== null && host.diskTotalBytes ? <Progress value={(diskUsed / host.diskTotalBytes) * 100} /> : null}
          </Stat>
        </Card>
        <Card>
          <Stat label="Load average" value={host.load ? host.load.map((l) => l.toFixed(2)).join('  ') : '–'} sub="1, 5 and 15 minutes" />
        </Card>
        <Card>
          <Stat label="Uptime" value={host.uptimeSeconds !== null ? duration(host.uptimeSeconds) : '–'} sub={`${apps.length} apps`} />
        </Card>
      </div>

      <div class="toolbar">
        <RangePicker value={minutes} onChange={setMinutes} />
      </div>
      <div class="charts">
        <Card title="Load" actions={<Legend series={[{ name: '1 min average', color: '#a78bfa' }]} />}>
          <Chart label="Load" times={times} series={[{ name: 'load', color: '#a78bfa', values: buckets.map((b) => b.load1) }]} kind="area" format={(n) => number(n, 2)} rangeMinutes={minutes} minMax={1} />
        </Card>
        <Card title="Memory used">
          <Chart label="Memory used" times={times} series={[{ name: 'used', color: '#60a5fa', values: buckets.map((b) => b.memoryUsedBytes) }]} kind="area" format={bytes} nice={niceBytes} rangeMinutes={minutes} />
        </Card>
      </div>

      <Card title="Edge">
        <div class="edge-grid">
          <div>
            <div class="muted">State</div>
            <Pill tone={edge.state === 'running' ? 'green' : 'red'} dot>
              {edge.state}
            </Pill>
          </div>
          <div>
            <div class="muted">Ports</div>
            <b>
              {edge.httpPort} / {edge.httpsPort}
            </b>
          </div>
          <div>
            <div class="muted">Requests served</div>
            <b>{edge.status ? number(edge.status.requests.total) : '–'}</b>
          </div>
          <div>
            <div class="muted">Connections</div>
            <b>{connections ? `${connections.active} active, ${connections.idle} idle` : '–'}</b>
          </div>
          <div>
            <div class="muted">Accepted</div>
            <b>{connections ? number(connections.accepted) : '–'}</b>
          </div>
        </div>
      </Card>

      <Card title="Edge logs">
        <LogViewer url="/api/host/logs?n=300" height={320} empty="The edge has not logged anything yet." />
      </Card>
    </div>
  )
}
