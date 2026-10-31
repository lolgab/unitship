import type { ComponentChildren } from 'preact'
import { createContext } from 'preact'
import { useContext } from 'preact/hooks'
import type { Status } from './api'
import { useApi, type Resource } from './hooks'

// What the sidebar, the dashboard and the header of an app all show, fetched once.
const StatusContext = createContext<Resource<Status> | undefined>(undefined)

export function StatusProvider({ children }: { children: ComponentChildren }) {
  const status = useApi<Status>('/api/status', 5000)
  return <StatusContext.Provider value={status}>{children}</StatusContext.Provider>
}

export function useStatus(): Resource<Status> {
  const status = useContext(StatusContext)
  if (!status) throw new Error('no StatusProvider')
  return status
}
