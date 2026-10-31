import type { ComponentChildren, JSX } from 'preact'
import { createContext } from 'preact'
import { useCallback, useContext, useEffect, useRef, useState } from 'preact/hooks'

const paths: Record<string, string> = {
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/>',
  server: '<rect x="2" y="2" width="20" height="8" rx="2"/><rect x="2" y="14" width="20" height="8" rx="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/>',
  activity: '<polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/>',
  key: '<circle cx="8" cy="15" r="4"/><line x1="10.85" y1="12.15" x2="21" y2="2"/><line x1="18" y1="5" x2="21" y2="8"/><line x1="15" y1="8" x2="17" y2="10"/>',
  globe: '<circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
  sliders: '<line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/>',
  logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/>',
  plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  play: '<polygon points="6 3 20 12 6 21 6 3"/>',
  stop: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  rollback: '<polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>',
  refresh: '<polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>',
  upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>',
  trash: '<polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>',
  lock: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  external: '<path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/>',
  check: '<polyline points="20 6 9 17 4 12"/>',
  x: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  alert: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
  box: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/>',
  ship: '<path d="M3 17l3-9h12l3 9"/><path d="M2 20c2 0 2-1 4-1s2 1 4 1 2-1 4-1 2 1 4 1 2-1 4-1"/><line x1="12" y1="3" x2="12" y2="8"/>',
  eye: '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>',
  chevron: '<polyline points="9 18 15 12 9 6"/>',
}

export function Icon({ name, size = 16, class: cls }: { name: keyof typeof paths | string; size?: number; class?: string }) {
  return (
    <svg
      class={`icon ${cls ?? ''}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      dangerouslySetInnerHTML={{ __html: paths[name] ?? '' }}
    />
  )
}

type ButtonProps = {
  kind?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  icon?: string
  busy?: boolean
} & Omit<JSX.IntrinsicElements['button'], 'size' | 'ref'>

export function Button({ kind = 'secondary', size = 'md', icon, busy, children, disabled, class: cls, ...rest }: ButtonProps) {
  return (
    <button {...rest} class={`btn btn-${kind} btn-${size} ${cls ?? ''}`} disabled={disabled || busy}>
      {busy ? <Spinner size={14} /> : icon ? <Icon name={icon} size={size === 'sm' ? 14 : 16} /> : null}
      {children}
    </button>
  )
}

export function Spinner({ size = 18 }: { size?: number }) {
  return <span class="spinner" style={{ width: `${size}px`, height: `${size}px` }} role="status" aria-label="loading" />
}

export type Tone = 'green' | 'red' | 'amber' | 'blue' | 'violet' | 'gray'

export function Pill({ tone = 'gray', children, dot }: { tone?: Tone; children: ComponentChildren; dot?: boolean }) {
  return (
    <span class={`pill pill-${tone}`}>
      {dot && <span class="dot" />}
      {children}
    </span>
  )
}

export function StatePill({ state }: { state: 'running' | 'stopped' | 'none' | string }) {
  const tone: Tone = state === 'running' ? 'green' : state === 'stopped' ? 'red' : 'gray'
  return (
    <Pill tone={tone} dot>
      {state === 'none' ? 'no release' : state}
    </Pill>
  )
}

export function Card({ title, actions, children, class: cls, flush }: { title?: ComponentChildren; actions?: ComponentChildren; children: ComponentChildren; class?: string; flush?: boolean }) {
  return (
    <section class={`card ${cls ?? ''}`}>
      {(title || actions) && (
        <header class="card-header">
          <h2>{title}</h2>
          <div class="card-actions">{actions}</div>
        </header>
      )}
      <div class={flush ? 'card-body flush' : 'card-body'}>{children}</div>
    </section>
  )
}

export function Stat({ label, value, sub, children }: { label: string; value: ComponentChildren; sub?: ComponentChildren; children?: ComponentChildren }) {
  return (
    <div class="stat">
      <div class="stat-label">{label}</div>
      <div class="stat-value">{value}</div>
      {sub && <div class="stat-sub">{sub}</div>}
      {children}
    </div>
  )
}

export function Progress({ value, tone }: { value: number; tone?: Tone }) {
  const pct = Math.max(0, Math.min(100, value))
  const t = tone ?? (pct > 90 ? 'red' : pct > 75 ? 'amber' : 'violet')
  return (
    <div class="progress" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
      <div class={`progress-bar tone-${t}`} style={{ width: `${pct}%` }} />
    </div>
  )
}

export function Empty({ icon = 'box', title, children }: { icon?: string; title: string; children?: ComponentChildren }) {
  return (
    <div class="empty">
      <Icon name={icon} size={28} />
      <h3>{title}</h3>
      {children && <p>{children}</p>}
    </div>
  )
}

export function ErrorNote({ error, retry }: { error: Error; retry?: () => void }) {
  return (
    <div class="note note-red" role="alert">
      <Icon name="alert" />
      <div>{error.message}</div>
      {retry && (
        <Button size="sm" onClick={retry}>
          Retry
        </Button>
      )}
    </div>
  )
}

export function Tabs({ tabs, active, onSelect }: { tabs: { id: string; label: string; icon?: string; badge?: string | number }[]; active: string; onSelect: (id: string) => void }) {
  return (
    <nav class="tabs" role="tablist">
      {tabs.map((t) => (
        <button key={t.id} role="tab" aria-selected={t.id === active} class={`tab ${t.id === active ? 'active' : ''}`} onClick={() => onSelect(t.id)}>
          {t.icon && <Icon name={t.icon} size={15} />}
          {t.label}
          {t.badge !== undefined && <span class="tab-badge">{t.badge}</span>}
        </button>
      ))}
    </nav>
  )
}

export function Modal({ title, onClose, children, footer, wide }: { title: string; onClose: () => void; children: ComponentChildren; footer?: ComponentChildren; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    ref.current?.querySelector<HTMLElement>('input, button.btn-primary, button')?.focus()
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div class="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div class={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <header>
          <h2>{title}</h2>
          <button class="icon-btn" onClick={onClose} aria-label="Close">
            <Icon name="x" />
          </button>
        </header>
        <div class="modal-body">{children}</div>
        {footer && <footer>{footer}</footer>}
      </div>
    </div>
  )
}

/** Output of a command of the host. */
export function Output({ text, ok }: { text: string; ok?: boolean }) {
  return <pre class={`output ${ok === false ? 'output-error' : ''}`}>{text || '(no output)'}</pre>
}

// ---- toasts

interface Toast {
  id: number
  kind: 'success' | 'error' | 'info'
  message: string
}
const ToastContext = createContext<(message: string, kind?: Toast['kind']) => void>(() => {})
export const useToast = () => useContext(ToastContext)

export function ToastProvider({ children }: { children: ComponentChildren }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const next = useRef(1)
  const show = useCallback((message: string, kind: Toast['kind'] = 'success') => {
    const id = next.current++
    setToasts((t) => [...t, { id, kind, message }])
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'error' ? 8000 : 4000)
  }, [])
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div class="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} class={`toast toast-${t.kind}`}>
            <Icon name={t.kind === 'error' ? 'alert' : 'check'} />
            <span>{t.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
