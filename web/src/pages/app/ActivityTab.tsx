import type { AppDetail, AppEvent } from '../../api'
import { Card, Empty, Icon } from '../../components/ui'
import { absolute, ago } from '../../format'
import { useNow } from '../../hooks'

const kinds: Record<string, { icon: string; tone: string; title: string }> = {
  create: { icon: 'plus', tone: 'blue', title: 'App created' },
  deploy: { icon: 'upload', tone: 'green', title: 'Deployed' },
  deploy_failed: { icon: 'alert', tone: 'red', title: 'Deploy failed' },
  config: { icon: 'key', tone: 'violet', title: 'Configuration' },
  config_failed: { icon: 'alert', tone: 'red', title: 'Configuration failed' },
  rollback: { icon: 'rollback', tone: 'amber', title: 'Rolled back' },
  rollback_failed: { icon: 'alert', tone: 'red', title: 'Rollback failed' },
  domain: { icon: 'globe', tone: 'blue', title: 'Domains' },
  tls: { icon: 'lock', tone: 'green', title: 'Certificate' },
  start: { icon: 'play', tone: 'green', title: 'Started' },
  stop: { icon: 'stop', tone: 'red', title: 'Stopped' },
  restart: { icon: 'refresh', tone: 'amber', title: 'Restarted' },
}

export function EventRow({ event, now }: { event: AppEvent; now: number }) {
  const kind = kinds[event.type] ?? { icon: 'box', tone: 'gray', title: event.type }
  return (
    <li class="event">
      <span class={`event-icon tone-${kind.tone}`}>
        <Icon name={kind.icon} size={14} />
      </span>
      <div class="event-text">
        <div>
          <b>{kind.title}</b>
          {event.release !== null && <span class="muted"> · release #{event.release}</span>}
        </div>
        {event.message !== kind.title && <div class="muted">{event.message}</div>}
      </div>
      <time class="muted" title={absolute(event.at)}>
        {ago(event.at, now)}
      </time>
    </li>
  )
}

export function ActivityTab({ app }: { app: AppDetail }) {
  const now = useNow()
  return (
    <Card title="Activity" flush>
      {app.events.length === 0 ? (
        <Empty icon="activity" title="No activity yet">
          Deploys, rollbacks, configuration and certificate changes appear here.
        </Empty>
      ) : (
        <ul class="timeline">
          {[...app.events].reverse().map((e, i) => (
            <EventRow key={i} event={e} now={now} />
          ))}
        </ul>
      )}
    </Card>
  )
}
