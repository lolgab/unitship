import { useMemo } from 'preact/hooks'
import type { AppDetail, Point } from '../../api'
import { Chart, Legend, niceBytes } from '../../components/Chart'
import { LogViewer } from '../../components/LogViewer'
import { Button, Card, ErrorNote, Spinner } from '../../components/ui'
import { bytes, milliseconds, number, percent } from '../../format'
import { useApi, useNow, useStored } from '../../hooks'
import { bucketize, ranges } from '../../metrics'

export const colors = { s2: '#34d399', s3: '#60a5fa', s4: '#fbbf24', s5: '#f87171', avg: '#a78bfa', p95: '#f472b6', memory: '#60a5fa', cpu: '#fbbf24' }

export function RangePicker({ value, onChange }: { value: number; onChange: (minutes: number) => void }) {
  return (
    <div class="segmented" role="group" aria-label="Time range">
      {ranges.map((r) => (
        <button key={r.minutes} class={r.minutes === value ? 'active' : ''} onClick={() => onChange(r.minutes)}>
          {r.label}
        </button>
      ))}
    </div>
  )
}

export function MonitoringTab({ app }: { app: AppDetail }) {
  const [minutes, setMinutes] = useStored<number>('range', 60)
  const now = useNow(30000)
  const metrics = useApi<Point[]>(`/api/apps/${app.name}/metrics?minutes=${minutes}`, 30000)
  const buckets = useMemo(() => (metrics.data ? bucketize(metrics.data, now, minutes) : []), [metrics.data, now, minutes])
  const times = buckets.map((b) => b.t)
  const total = metrics.data?.reduce((a, p) => a + p.requests, 0) ?? 0
  const errors = metrics.data?.reduce((a, p) => a + p.s5, 0) ?? 0
  const hasCpu = buckets.some((b) => b.cpuPct !== null)

  const requests = [
    { name: '2xx', color: colors.s2, values: buckets.map((b) => b.s2) },
    { name: '3xx', color: colors.s3, values: buckets.map((b) => b.s3) },
    { name: '4xx', color: colors.s4, values: buckets.map((b) => b.s4) },
    { name: '5xx', color: colors.s5, values: buckets.map((b) => b.s5) },
  ]
  const latency = [
    { name: 'average', color: colors.avg, values: buckets.map((b) => b.avgMs) },
    { name: 'p95', color: colors.p95, values: buckets.map((b) => b.p95Ms) },
  ]

  return (
    <div class="monitoring">
      <div class="toolbar">
        <RangePicker value={minutes} onChange={setMinutes} />
        {metrics.loading && <Spinner size={16} />}
        <span class="muted">
          {number(total)} requests{total > 0 ? `, ${percent((errors / total) * 100)} server errors` : ''}
        </span>
      </div>
      {metrics.error && !metrics.data && <ErrorNote error={metrics.error} retry={metrics.reload} />}

      <div class="charts">
        <Card title="Requests per minute" actions={<Legend series={requests} />}>
          <Chart label="Requests per minute by status class" times={times} series={requests} kind="stack" format={(n) => number(n)} rangeMinutes={minutes} />
        </Card>
        <Card title="Response time" actions={<Legend series={latency} />}>
          <Chart label="Response time" times={times} series={latency} kind="line" format={milliseconds} rangeMinutes={minutes} />
        </Card>
        <Card title="Memory">
          <Chart label="Memory" times={times} series={[{ name: 'memory', color: colors.memory, values: buckets.map((b) => b.memoryBytes) }]} kind="area" format={bytes} nice={niceBytes} rangeMinutes={minutes} />
        </Card>
        <Card title="CPU">
          {hasCpu || minutes > 0 ? (
            <Chart label="CPU" times={times} series={[{ name: 'CPU', color: colors.cpu, values: buckets.map((b) => b.cpuPct) }]} kind="area" format={(n) => `${number(n)}%`} rangeMinutes={minutes} minMax={10} />
          ) : null}
          {!hasCpu && <div class="chart-note">CPU use is measured when the apps run under systemd.</div>}
        </Card>
      </div>

      <Card title="Logs" actions={<Button size="sm" onClick={() => (window.location.hash = `/apps/${app.name}/activity`)}>Activity</Button>}>
        <LogViewer url={`/api/apps/${app.name}/logs?n=300`} empty="This app has not logged anything yet." />
      </Card>
    </div>
  )
}
