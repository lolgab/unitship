import { describe, expect, it } from 'vitest'
import type { Point } from './api'
import { bucketMs, bucketize, bucketizeHost, currentRate } from './metrics'
import { ago, bytes, daysUntil, duration, milliseconds } from './format'

const point = (ms: number, extra: Partial<Point> = {}): Point => ({
  ms, t: '', seconds: 60, requests: 60, s2: 60, s3: 0, s4: 0, s5: 0, avgMs: 10, p95Ms: 20, bytes: 0, memoryBytes: null, cpuPct: null, ...extra,
})

describe('buckets', () => {
  it('are a minute or more and about ninety at most', () => {
    expect(bucketMs(15)).toBe(60_000)
    expect(bucketMs(60)).toBe(60_000)
    expect(bucketMs(360)).toBe(4 * 60_000)
    expect(bucketMs(1440)).toBe(16 * 60_000)
    expect(bucketMs(10080)).toBe(112 * 60_000)
  })

  it('turn points into rates per minute and leave gaps', () => {
    const now = 10_000_000
    const buckets = bucketize([point(now - 60_000), point(now, { requests: 120, s2: 100, s4: 20 })], now, 15)
    expect(buckets).toHaveLength(15)
    const last = buckets[14]!
    expect(last.t).toBe(now)
    expect(last.rate).toBe(120)
    expect(last.s2).toBe(100)
    expect(last.s4).toBe(20)
    expect(buckets[13]!.rate).toBe(60)
    expect(buckets[0]!.rate).toBeNull()
  })

  it('weight the latency by the requests and take the worst p95', () => {
    const now = 10_000_000
    const [only] = bucketize(
      [point(now - 100_000, { requests: 10, avgMs: 100, p95Ms: 300 }), point(now - 40_000, { requests: 90, avgMs: 10, p95Ms: 50 })],
      now,
      360,
    ).filter((b) => b.rate !== null)
    expect(only!.avgMs).toBeCloseTo(19, 5)
    expect(only!.p95Ms).toBe(300)
  })

  it('count the seconds that were covered, not the size of the bucket', () => {
    const now = 10_000_000
    const buckets = bucketize([point(now, { seconds: 30, requests: 30 })], now, 15)
    expect(buckets[14]!.rate).toBe(60)
  })

  it('ignore points out of the range and take the last memory and the mean CPU', () => {
    const now = 10_000_000
    const buckets = bucketize(
      [point(now - 20 * 60_000), point(now - 30_000, { memoryBytes: 1, cpuPct: 10 }), point(now - 10_000, { memoryBytes: 2, cpuPct: 30 })],
      now,
      15,
    )
    expect(buckets.filter((b) => b.rate !== null)).toHaveLength(1)
    expect(buckets[14]!.memoryBytes).toBe(2)
    expect(buckets[14]!.cpuPct).toBe(20)
  })

  it('of the host', () => {
    const now = 10_000_000
    const buckets = bucketizeHost(
      [{ ms: now, t: '', load1: 2, memoryAvailableBytes: 300, memoryTotalBytes: 1000 }, { ms: now - 1, t: '', load1: 4, memoryAvailableBytes: 400, memoryTotalBytes: 1000 }],
      now,
      15,
    )
    expect(buckets[14]!.load1).toBe(3)
    expect(buckets[14]!.memoryUsedBytes).toBe(700)
    expect(buckets[0]!.load1).toBeNull()
  })

  it('give the current rate of the last point', () => {
    expect(currentRate([])).toBeNull()
    expect(currentRate([point(1, { requests: 30, seconds: 60 })])).toBe(30)
  })
})

describe('formats', () => {
  it('bytes', () => {
    expect(bytes(null)).toBe('–')
    expect(bytes(512)).toBe('512 B')
    expect(bytes(1536)).toBe('1.5 KB')
    expect(bytes(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(bytes(450 * 1024 ** 3)).toBe('450 GB')
  })
  it('milliseconds', () => {
    expect(milliseconds(0.25)).toBe('0.3 ms')
    expect(milliseconds(0.04)).toBe('<0.1 ms')
    expect(milliseconds(0)).toBe('0.0 ms')
    expect(milliseconds(42.4)).toBe('42 ms')
    expect(milliseconds(1500)).toBe('1.50 s')
  })
  it('times', () => {
    const now = Date.parse('2026-10-25T10:00:00Z')
    expect(ago('2026-10-25T09:59:58Z', now)).toBe('just now')
    expect(ago('2026-10-25T09:59:00Z', now)).toBe('1m ago')
    expect(ago('2026-10-25T07:00:00Z', now)).toBe('3h ago')
    expect(ago('2026-10-20T10:00:00Z', now)).toBe('5d ago')
    expect(ago(null, now)).toBe('–')
    expect(daysUntil('2026-11-04T10:00:00Z', now)).toBe(10)
    expect(daysUntil('2026-10-24T10:00:00Z', now)).toBe(-1)
    expect(duration(90061)).toBe('1d 1h')
    expect(duration(3700)).toBe('1h 1m')
  })
})
