import { useEffect, useRef, useState } from 'preact/hooks'
import { absolute, clock } from '../format'

export interface Series {
  name: string
  color: string
  values: (number | null)[]
}

interface ChartProps {
  times: number[]
  series: Series[]
  kind: 'line' | 'area' | 'stack'
  format: (n: number) => string
  /** The span of the chart, to label the time axis with the date when it is longer than a day. */
  rangeMinutes: number
  height?: number
  label: string
  /** The top of the scale when the data is smaller, for percentages. */
  minMax?: number
  /** Rounds the top of the scale. The default is 1, 2, 5, 10... */
  nice?: (value: number) => number
}

/** 1, 2, 5 or 10 times a power of ten, not less than `value`. */
export function niceMax(value: number): number {
  if (value <= 0) return 1
  const exponent = Math.floor(Math.log10(value))
  const base = Math.pow(10, exponent)
  const fraction = value / base
  return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * base
}

/** A top for a scale of bytes that is round in the unit that is shown: 1, 2, 5 or 10 KB, MB, GB... */
export function niceBytes(value: number): number {
  if (value <= 0) return 1
  const unit = Math.min(4, Math.max(0, Math.floor(Math.log(value) / Math.log(1024))))
  const base = Math.pow(1024, unit)
  return niceMax(value / base) * base
}

let ids = 0

export function Chart({ times, series, kind, format, rangeMinutes, height = 180, label, minMax = 0, nice = niceMax }: ChartProps) {
  const holder = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(640)
  const [hover, setHover] = useState<number | null>(null)
  const [id] = useState(() => `c${ids++}`)

  useEffect(() => {
    const element = holder.current
    if (!element) return
    setWidth(element.clientWidth || 640)
    const observer = new ResizeObserver(() => setWidth(element.clientWidth || 640))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const n = times.length
  const pad = { l: 48, r: 10, t: 10, b: 22 }
  const w = Math.max(80, width - pad.l - pad.r)
  const h = height - pad.t - pad.b
  const slot = n > 0 ? w / n : w
  const cx = (i: number) => (i + 0.5) * slot

  const stacked = kind === 'stack'
  const top = times.map((_, i) => {
    const values = series.map((s) => s.values[i]).filter((v): v is number => v !== null && v !== undefined)
    return values.length === 0 ? 0 : stacked ? values.reduce((a, b) => a + b, 0) : Math.max(...values)
  })
  const max = nice(Math.max(minMax, ...top))
  const y = (v: number) => h - (v / max) * h
  const hasData = series.some((s) => s.values.some((v) => v !== null && v !== undefined))

  const segments = (values: (number | null)[]): number[][][] => {
    const out: number[][][] = []
    let current: number[][] = []
    values.forEach((v, i) => {
      if (v === null || v === undefined) {
        if (current.length) out.push(current)
        current = []
      } else current.push([cx(i), y(v)])
    })
    if (current.length) out.push(current)
    return out
  }

  const every = Math.max(1, Math.ceil(n / 6))
  const xTicks = times.map((t, i) => ({ t, i })).filter(({ i }) => (n - 1 - i) % every === 0)

  const onMove = (e: MouseEvent) => {
    const box = (e.currentTarget as SVGElement).getBoundingClientRect()
    const x = e.clientX - box.left - pad.l
    setHover(Math.max(0, Math.min(n - 1, Math.floor(x / slot))))
  }

  return (
    <div class="chart" ref={holder}>
      {!hasData ? (
        <div class="chart-empty" style={{ height: `${height}px` }}>
          No data yet
        </div>
      ) : (
        <>
          <svg width={width} height={height} role="img" aria-label={label} onMouseMove={onMove} onMouseLeave={() => setHover(null)}>
            <defs>
              {series.map((s, k) => (
                <linearGradient id={`${id}-${k}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color={s.color} stop-opacity="0.35" />
                  <stop offset="100%" stop-color={s.color} stop-opacity="0.02" />
                </linearGradient>
              ))}
            </defs>
            <g transform={`translate(${pad.l},${pad.t})`}>
              {[0, 1, 2, 3, 4].map((k) => (
                <g key={k}>
                  <line class="grid" x1="0" x2={w} y1={y((max * k) / 4)} y2={y((max * k) / 4)} />
                  <text class="axis" x="-8" y={y((max * k) / 4) + 4} text-anchor="end">
                    {format((max * k) / 4)}
                  </text>
                </g>
              ))}
              {xTicks.map(({ t, i }) => (
                <text key={i} class="axis" x={cx(i)} y={h + 16} text-anchor="middle">
                  {clock(t, rangeMinutes > 1440)}
                </text>
              ))}

              {stacked &&
                times.map((_, i) => {
                  let base = 0
                  return series.map((s) => {
                    const v = s.values[i]
                    if (v === null || v === undefined || v <= 0) return null
                    const rect = <rect key={`${i}-${s.name}`} x={i * slot + slot * 0.12} width={Math.max(1, slot * 0.76)} y={y(base + v)} height={Math.max(0.5, (v / max) * h)} fill={s.color} rx="1" />
                    base += v
                    return rect
                  })
                })}

              {!stacked &&
                series.map((s, k) =>
                  segments(s.values).map((seg, j) => {
                    const line = seg.map((p, m) => `${m === 0 ? 'M' : 'L'}${p[0]!.toFixed(1)},${p[1]!.toFixed(1)}`).join(' ')
                    const [first, last] = [seg[0]!, seg[seg.length - 1]!]
                    return (
                      <g key={`${k}-${j}`}>
                        {kind === 'area' && <path d={`${line} L${last[0]!.toFixed(1)},${h} L${first[0]!.toFixed(1)},${h} Z`} fill={`url(#${id}-${k})`} />}
                        <path d={line} fill="none" stroke={s.color} stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />
                        {seg.length === 1 && <circle cx={first[0]} cy={first[1]} r="2.5" fill={s.color} />}
                      </g>
                    )
                  }),
                )}

              {hover !== null && (
                <g>
                  <line class="crosshair" x1={cx(hover)} x2={cx(hover)} y1="0" y2={h} />
                  {!stacked &&
                    series.map((s) => {
                      const v = s.values[hover]
                      return v === null || v === undefined ? null : <circle key={s.name} cx={cx(hover)} cy={y(v)} r="4" fill={s.color} stroke="var(--surface)" stroke-width="2" />
                    })}
                </g>
              )}
            </g>
          </svg>
          {hover !== null && times[hover] !== undefined && (
            <div class="chart-tip" style={{ left: `${Math.min(width - 170, Math.max(8, pad.l + cx(hover) + 12))}px`, top: '6px' }}>
              <div class="chart-tip-time">{absolute(new Date(times[hover]!).toISOString())}</div>
              {series.map((s) => (
                <div key={s.name} class="chart-tip-row">
                  <span class="swatch" style={{ background: s.color }} />
                  <span>{s.name}</span>
                  <b>{s.values[hover] === null || s.values[hover] === undefined ? '–' : format(s.values[hover]!)}</b>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}

export function Legend({ series }: { series: { name: string; color: string }[] }) {
  return (
    <div class="legend">
      {series.map((s) => (
        <span key={s.name}>
          <span class="swatch" style={{ background: s.color }} />
          {s.name}
        </span>
      ))}
    </div>
  )
}

export function Sparkline({ values, color = 'var(--accent)', width = 120, height = 34 }: { values: (number | null)[]; color?: string; width?: number; height?: number }) {
  const present = values.filter((v): v is number => v !== null)
  if (present.length < 2) return <svg class="spark" width={width} height={height} aria-hidden="true" />
  const max = Math.max(...present, 1e-9)
  const n = values.length
  const step = width / Math.max(1, n - 1)
  let line = ''
  let first = 0
  let last = 0
  values.forEach((v, i) => {
    if (v === null) return
    const point = `${(i * step).toFixed(1)},${(height - 3 - (v / max) * (height - 6)).toFixed(1)}`
    line += (line === '' ? 'M' : 'L') + point
    if (line.length === point.length + 1) first = i * step
    last = i * step
  })
  return (
    <svg class="spark" width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
      <path d={`${line} L${last.toFixed(1)},${height} L${first.toFixed(1)},${height} Z`} fill={color} opacity="0.15" />
      <path d={line} fill="none" stroke={color} stroke-width="1.6" stroke-linejoin="round" stroke-linecap="round" />
    </svg>
  )
}
