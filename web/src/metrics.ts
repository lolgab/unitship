import type { Point, HostPoint } from './api'

// Points arrive when the host collected them, about one a minute, covering `seconds` each. The charts want regular steps.

export interface Bucket {
  /** End of the bucket, milliseconds. */
  t: number
  /** Requests per minute, in total and by status class. None where nothing was collected. */
  rate: number | null
  s2: number | null
  s3: number | null
  s4: number | null
  s5: number | null
  avgMs: number | null
  p95Ms: number | null
  memoryBytes: number | null
  cpuPct: number | null
}

export interface Range {
  label: string
  minutes: number
}

export const ranges: Range[] = [
  { label: '15 min', minutes: 15 },
  { label: '1 hour', minutes: 60 },
  { label: '6 hours', minutes: 360 },
  { label: '24 hours', minutes: 1440 },
  { label: '7 days', minutes: 10080 },
]

/** A bucket is a minute at least, and at most ~90 of them are drawn. */
export function bucketMs(minutes: number): number {
  return Math.max(60_000, Math.ceil((minutes * 60_000) / 90 / 60_000) * 60_000)
}

export function bucketize(points: Point[], now: number, minutes: number): Bucket[] {
  const size = bucketMs(minutes)
  const count = Math.ceil((minutes * 60_000) / size)
  const start = now - count * size
  const buckets: Point[][] = Array.from({ length: count }, () => [])
  for (const p of [...points].sort((x, y) => x.ms - y.ms)) {
    const index = Math.floor((p.ms - start - 1) / size)
    if (index >= 0 && index < count) buckets[index]!.push(p)
  }
  return buckets.map((ps, i) => {
    const t = start + (i + 1) * size
    if (ps.length === 0) return { t, rate: null, s2: null, s3: null, s4: null, s5: null, avgMs: null, p95Ms: null, memoryBytes: null, cpuPct: null }
    const seconds = ps.reduce((a, p) => a + p.seconds, 0)
    const perMinute = (f: (p: Point) => number) => (ps.reduce((a, p) => a + f(p), 0) / seconds) * 60
    const requests = ps.reduce((a, p) => a + p.requests, 0)
    const memory = ps.filter((p) => p.memoryBytes !== null)
    const cpu = ps.filter((p) => p.cpuPct !== null)
    return {
      t,
      rate: perMinute((p) => p.requests),
      s2: perMinute((p) => p.s2),
      s3: perMinute((p) => p.s3),
      s4: perMinute((p) => p.s4),
      s5: perMinute((p) => p.s5),
      // Weighted by requests, so a quiet minute does not count as much as a busy one.
      avgMs: requests > 0 ? ps.reduce((a, p) => a + p.avgMs * p.requests, 0) / requests : null,
      p95Ms: requests > 0 ? Math.max(...ps.filter((p) => p.requests > 0).map((p) => p.p95Ms)) : null,
      memoryBytes: memory.length ? memory[memory.length - 1]!.memoryBytes : null,
      cpuPct: cpu.length ? cpu.reduce((a, p) => a + p.cpuPct!, 0) / cpu.length : null,
    }
  })
}

export interface HostBucket {
  t: number
  load1: number | null
  memoryUsedBytes: number | null
}

export function bucketizeHost(points: HostPoint[], now: number, minutes: number): HostBucket[] {
  const size = bucketMs(minutes)
  const count = Math.ceil((minutes * 60_000) / size)
  const start = now - count * size
  return Array.from({ length: count }, (_, i) => {
    const t = start + (i + 1) * size
    const ps = points.filter((p) => p.ms > t - size && p.ms <= t)
    const last = ps.reduce<HostPoint | undefined>((a, p) => (a === undefined || p.ms > a.ms ? p : a), undefined)
    const loads = ps.filter((p) => p.load1 !== null)
    return {
      t,
      load1: loads.length ? loads.reduce((a, p) => a + p.load1!, 0) / loads.length : null,
      memoryUsedBytes: last && last.memoryTotalBytes !== null && last.memoryAvailableBytes !== null ? last.memoryTotalBytes - last.memoryAvailableBytes : null,
    }
  })
}

/** The requests per minute of the last point that has data, for a summary. */
export function currentRate(points: Point[]): number | null {
  const last = points[points.length - 1]
  return last && last.seconds > 0 ? (last.requests / last.seconds) * 60 : null
}

export function totalRequests(points: Point[]): number {
  return points.reduce((a, p) => a + p.requests, 0)
}
