// The API of the panel (see panel/src/Server.scala) and its types.

export type AppState = 'running' | 'stopped' | 'none'

export interface Release {
  id: number
  port: number | null
  active: boolean
  previous: boolean
  state: 'running' | 'stopped'
  createdAt: string
  sizeBytes: number | null
  memoryBytes: number | null
  cpuNanos: number | null
}

export interface Domain {
  name: string
  https: boolean
  certificateExpires: string | null
}

export interface AppEvent {
  at: string
  type: string
  release: number | null
  message: string
}

export interface AppDetail {
  name: string
  domains: Domain[]
  active: number | null
  previous: number | null
  releases: Release[]
  env: string[]
  events: AppEvent[]
}

export interface AppSummary {
  name: string
  domains: string[]
  release: number | null
  port: number | null
  state: AppState
  https: boolean
  httpsDomains: string[]
  memoryBytes: number | null
}

export interface HostInfo {
  load: number[] | null
  memoryTotalBytes: number | null
  memoryAvailableBytes: number | null
  uptimeSeconds: number | null
  diskTotalBytes: number | null
  diskAvailableBytes: number | null
}

export interface Status {
  host: HostInfo
  edge: {
    state: 'running' | 'stopped'
    httpPort: number
    httpsPort: number
    accessLog: string
    status: { connections: { accepted: number; active: number; idle: number; closed: number }; requests: { total: number } } | null
  }
  apps: AppSummary[]
}

/** The edge served `requests` to an app during the `seconds` that end at `ms`. */
export interface Point {
  ms: number
  t: string
  seconds: number
  requests: number
  s2: number
  s3: number
  s4: number
  s5: number
  avgMs: number
  p95Ms: number
  bytes: number
  memoryBytes: number | null
  cpuPct: number | null
}

export interface HostPoint {
  ms: number
  t: string
  load1: number | null
  memoryAvailableBytes: number | null
  memoryTotalBytes: number | null
}

export interface LogLines {
  lines: string[]
  error: string | null
}

export interface ActionResult {
  ok: boolean
  output: string
}

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly output?: string,
  ) {
    super(message)
  }
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(handler: () => void): void {
  onUnauthorized = handler
}

async function request<T>(method: string, path: string, body?: unknown, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body === undefined || body instanceof Blob ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body),
    ...init,
  })
  const text = await response.text()
  let data: any = undefined
  try {
    data = text ? JSON.parse(text) : undefined
  } catch {
    // Not JSON (a proxy error page): reported below.
  }
  if (response.status === 401 && path !== '/api/login') onUnauthorized()
  if (!response.ok) {
    throw new ApiError(data?.error ?? (data?.output as string | undefined)?.split('\n').pop() ?? `${response.status} ${response.statusText}`, response.status, data?.output)
  }
  return data as T
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>('GET', path, undefined, { signal }),
  post: <T>(path: string, body: unknown = {}) => request<T>('POST', path, body),
  put: <T>(path: string, body: unknown) => request<T>('PUT', path, body),
  delete: <T>(path: string, body: unknown) => request<T>('DELETE', path, body),

  session: () => request<{ authenticated: boolean; configured: boolean }>('GET', '/api/session'),
  login: (password: string) => request<{ ok: boolean }>('POST', '/api/login', { password }),
  logout: () => request<{ ok: boolean }>('POST', '/api/logout', {}),
}

/** Uploads an executable with progress. XMLHttpRequest, because fetch cannot report how much has been sent. */
export function deploy(app: string, file: File, onProgress: (fraction: number) => void): Promise<ActionResult> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', `/api/apps/${encodeURIComponent(app)}/deploy`)
    xhr.setRequestHeader('Content-Type', 'application/octet-stream')
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
    xhr.onerror = () => reject(new ApiError('the upload failed', 0))
    xhr.onload = () => {
      let data: any
      try {
        data = JSON.parse(xhr.responseText)
      } catch {
        // Handled below.
      }
      if (xhr.status === 401) onUnauthorized()
      if (xhr.status >= 200 && xhr.status < 300) resolve(data as ActionResult)
      else reject(new ApiError(data?.error ?? data?.output?.split('\n').pop() ?? `${xhr.status}`, xhr.status, data?.output))
    }
    xhr.send(file)
  })
}
