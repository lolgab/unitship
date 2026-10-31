export function bytes(n: number | null | undefined): string {
  if (n === null || n === undefined) return '–'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = n
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

export function number(n: number | null | undefined, digits = 1): string {
  if (n === null || n === undefined) return '–'
  if (Math.abs(n) >= 1000) return `${(n / 1000).toFixed(1)}k`
  return n >= 100 || Number.isInteger(n) ? String(Math.round(n)) : n.toFixed(digits)
}

export function percent(n: number | null | undefined): string {
  return n === null || n === undefined ? '–' : `${n < 10 ? n.toFixed(1) : Math.round(n)}%`
}

export function milliseconds(n: number | null | undefined): string {
  if (n === null || n === undefined) return '–'
  return n >= 1000 ? `${(n / 1000).toFixed(2)} s` : n >= 10 ? `${Math.round(n)} ms` : n > 0 && n < 0.1 ? '<0.1 ms' : `${n.toFixed(1)} ms`
}

export function ago(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '–'
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000))
  if (seconds < 5) return 'just now'
  if (seconds < 60) return `${seconds}s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  return `${Math.floor(seconds / 86400)}d ago`
}

export function duration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '–'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return d > 0 ? `${d}d ${h}h` : h > 0 ? `${h}h ${m}m` : `${m}m`
}

/** Days until `iso`, rounded down. Negative if it passed. */
export function daysUntil(iso: string | null | undefined, now = Date.now()): number | null {
  return iso ? Math.floor((Date.parse(iso) - now) / 86400000) : null
}

export function clock(ms: number, withDate = false): string {
  const d = new Date(ms)
  const time = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  return withDate ? `${d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} ${time}` : time
}

export function absolute(iso: string): string {
  return new Date(iso).toLocaleString()
}

/** CPU time used, from nanoseconds. */
export function cpuTime(nanos: number | null | undefined): string {
  if (nanos === null || nanos === undefined) return '–'
  const s = nanos / 1e9
  return s < 60 ? `${s.toFixed(1)} s` : s < 3600 ? `${Math.floor(s / 60)}m ${Math.round(s % 60)}s` : `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`
}
