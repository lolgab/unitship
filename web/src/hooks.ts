import { useCallback, useEffect, useRef, useState } from 'preact/hooks'
import { api } from './api'

export interface Resource<T> {
  data: T | undefined
  error: Error | undefined
  /** True until the first answer. A refresh keeps the previous data. */
  loading: boolean
  reload: () => void
}

/** Fetches `path` (nothing if null) and again every `interval` milliseconds while the page is visible. */
export function useApi<T>(path: string | null, interval?: number): Resource<T> {
  const [data, setData] = useState<T | undefined>(undefined)
  const [error, setError] = useState<Error | undefined>(undefined)
  const [loading, setLoading] = useState(path !== null)
  const [tick, setTick] = useState(0)
  const previous = useRef<string | null>(null)

  useEffect(() => {
    if (path === null) return
    if (previous.current !== path) {
      // Another resource: do not show the data of the old one.
      setData(undefined)
      setError(undefined)
      setLoading(true)
      previous.current = path
    }
    let cancelled = false
    const controller = new AbortController()
    const load = () => {
      api
        .get<T>(path, controller.signal)
        .then((d) => {
          if (cancelled) return
          setData(d)
          setError(undefined)
          setLoading(false)
        })
        .catch((e) => {
          if (cancelled || e?.name === 'AbortError') return
          setError(e)
          setLoading(false)
        })
    }
    load()
    const timer = interval ? window.setInterval(() => !document.hidden && load(), interval) : undefined
    return () => {
      cancelled = true
      controller.abort()
      if (timer !== undefined) window.clearInterval(timer)
    }
  }, [path, interval, tick])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  return { data, error, loading, reload }
}

/** The parts of the hash: `#/apps/blog/monitoring` is ['apps', 'blog', 'monitoring']. */
export function useRoute(): string[] {
  const read = () => window.location.hash.replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent)
  const [route, setRoute] = useState(read)
  useEffect(() => {
    const onChange = () => setRoute(read())
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return route
}

export function navigate(...parts: string[]): void {
  window.location.hash = '/' + parts.map(encodeURIComponent).join('/')
}

/** Re-renders every `ms`, for the "3m ago" of the page. */
export function useNow(ms = 15000): number {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), ms)
    return () => window.clearInterval(t)
  }, [ms])
  return now
}

/** Remembers a choice (the time range of the charts) in the browser. */
export function useStored<T extends string | number>(key: string, initial: T): [T, (value: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const stored = window.localStorage.getItem(key)
      if (stored !== null) return (typeof initial === 'number' ? Number(stored) : stored) as T
    } catch {
      // Storage may be blocked: the choice is not kept.
    }
    return initial
  })
  const set = useCallback(
    (v: T) => {
      setValue(v)
      try {
        window.localStorage.setItem(key, String(v))
      } catch {
        // Not kept.
      }
    },
    [key],
  )
  return [value, set]
}
