import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { LogLines } from '../api'
import { useApi } from '../hooks'
import { Button, Icon } from './ui'

const level = (line: string) => (/\b(error|fatal|panic|exception|alert)\b/i.test(line) ? 'error' : /\b(warn|warning)\b/i.test(line) ? 'warn' : '')

/** The last lines of a log, refreshed every two seconds, in the manner of `tail -f`. */
export function LogViewer({ url, height = 360, empty = 'No logs yet.' }: { url: string; height?: number; empty?: string }) {
  const [paused, setPaused] = useState(false)
  const [filter, setFilter] = useState('')
  const [wrap, setWrap] = useState(false)
  const logs = useApi<LogLines>(paused ? null : url, 2000)
  const [kept, setKept] = useState<LogLines | undefined>(undefined)
  const box = useRef<HTMLDivElement>(null)
  const stick = useRef(true)

  useEffect(() => {
    if (logs.data) setKept(logs.data)
  }, [logs.data])

  const lines = useMemo(() => {
    const all = kept?.lines ?? []
    const f = filter.trim().toLowerCase()
    return f ? all.filter((l) => l.toLowerCase().includes(f)) : all
  }, [kept, filter])

  useEffect(() => {
    const el = box.current
    if (el && stick.current) el.scrollTop = el.scrollHeight
  }, [lines])

  const onScroll = () => {
    const el = box.current
    if (el) stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40
  }

  return (
    <div class="logs">
      <div class="logs-bar">
        <label class="search">
          <Icon name="eye" size={14} />
          <input value={filter} onInput={(e) => setFilter((e.target as HTMLInputElement).value)} placeholder="Filter" spellcheck={false} />
        </label>
        <label class="check">
          <input type="checkbox" checked={wrap} onChange={() => setWrap(!wrap)} /> Wrap
        </label>
        <Button size="sm" icon={paused ? 'play' : 'stop'} onClick={() => setPaused(!paused)}>
          {paused ? 'Resume' : 'Pause'}
        </Button>
        <span class={`live ${paused ? '' : 'on'}`}>{paused ? 'paused' : 'live'}</span>
      </div>
      <div class={`log-lines ${wrap ? 'wrap' : ''}`} style={{ height: `${height}px` }} ref={box} onScroll={onScroll} role="log" aria-live="off">
        {lines.length === 0 ? (
          <div class="log-empty">{kept?.error ? kept.error : filter ? 'No line matches.' : empty}</div>
        ) : (
          lines.map((l, i) => (
            <div key={i} class={`log-line ${level(l)}`}>
              {l}
            </div>
          ))
        )}
      </div>
    </div>
  )
}
