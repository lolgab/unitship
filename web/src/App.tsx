import { useEffect, useState } from 'preact/hooks'
import { api, setUnauthorizedHandler } from './api'
import { useRoute } from './hooks'
import { StatusProvider, useStatus } from './status'
import { Icon, Spinner, ToastProvider, useToast } from './components/ui'
import { Login } from './pages/Login'
import { Dashboard } from './pages/Dashboard'
import { AppPage } from './pages/AppPage'
import { HostPage } from './pages/HostPage'

export function App() {
  const [session, setSession] = useState<{ authenticated: boolean; configured: boolean } | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    setUnauthorizedHandler(() => setSession((s) => (s ? { ...s, authenticated: false } : s)))
    api.session().then(setSession).catch((e) => setFailed(e.message))
  }, [])

  return (
    <ToastProvider>
      {failed ? (
        <div class="splash">The panel cannot be reached: {failed}</div>
      ) : !session ? (
        <div class="splash">
          <Spinner size={28} />
        </div>
      ) : !session.authenticated ? (
        <Login configured={session.configured} onLoggedIn={() => setSession({ authenticated: true, configured: true })} />
      ) : (
        <StatusProvider>
          <Shell onLogout={() => setSession({ ...session, authenticated: false })} />
        </StatusProvider>
      )}
    </ToastProvider>
  )
}

function Shell({ onLogout }: { onLogout: () => void }) {
  const route = useRoute()
  const toast = useToast()
  const status = useStatus()

  const logout = async () => {
    try {
      await api.logout()
    } catch (e) {
      toast((e as Error).message, 'error')
    }
    onLogout()
  }

  const section = route[0] === 'host' ? 'host' : 'apps'
  return (
    <div class="shell">
      <aside class="sidebar">
        <a class="brand" href="#/">
          <span class="brand-mark">
            <Icon name="ship" size={18} />
          </span>
          unitship
        </a>
        <nav class="nav">
          <a href="#/" class={section === 'apps' && route.length === 0 ? 'active' : ''}>
            <Icon name="grid" /> Dashboard
          </a>
          <a href="#/host" class={section === 'host' ? 'active' : ''}>
            <Icon name="server" /> Host
          </a>
        </nav>
        <div class="nav-title">Apps</div>
        <nav class="nav apps-nav">
          {status.data?.apps.map((a) => (
            <a key={a.name} href={`#/apps/${a.name}`} class={route[0] === 'apps' && route[1] === a.name ? 'active' : ''}>
              <span class={`dot dot-${a.state === 'running' ? 'green' : a.state === 'stopped' ? 'red' : 'gray'}`} />
              <span class="truncate">{a.name}</span>
            </a>
          ))}
          {status.data?.apps.length === 0 && <div class="nav-empty">No apps yet</div>}
        </nav>
        <div class="sidebar-footer">
          <button class="nav-button" onClick={logout}>
            <Icon name="logout" /> Log out
          </button>
        </div>
      </aside>
      <main class="main">
        {route[0] === 'apps' && route[1] ? (
          <AppPage key={route[1]} name={route[1]} tab={route[2] ?? 'overview'} />
        ) : route[0] === 'host' ? (
          <HostPage />
        ) : (
          <Dashboard />
        )}
      </main>
    </div>
  )
}

