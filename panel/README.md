# The panel

A web page to see and manage the apps of a unitship host: the apps and their state, releases with rollbacks, deploys by upload,
secrets, domains and certificates, request, latency, memory and CPU charts, live logs and the activity of every app.

It is an SNUnit application that unitship runs like any other, and it does nothing by itself: everything goes through the
`unitship` command line tool of the host (`unitship api ...` for what it shows, the commands for what it changes). So the panel
and the command line always agree, and the panel keeps no state besides its password.

```
browser ──https──▶ edge ──▶ panel (SNUnit) ──▶ unitship api status | api app | api metrics ... ──▶ ~/.unitship
```

## Install

On the server, with `unitship` already set up:

```bash
unitship create panel
unitship domains panel add panel.example.com
unitship deploy panel ./panel                   # built with panel/build.sh
unitship exec panel set-password                # asks for the password twice
unitship tls panel                              # HTTPS: the cookie of the session needs it
```

`--host user@server` runs these from your machine (`exec` opens a terminal for the password). The panel finds the host through
`UNITSHIP_HOME` and `UNITSHIP_BIN`, which unitship gives to every app.

To try it on http, on a machine of yours: `unitship config panel set PANEL_INSECURE=1`.

## Build

```bash
panel/build.sh dist/panel            # npm ci, builds the page, embeds it, snunit package
```

The page (`web/`) is built with Vite, gzipped by `web/embed.mjs` and written as `panel/gen/Assets.scala`, so the panel stays one
executable. The panel is built for the machine that runs `snunit package`: build it on, or for, the server.

## Security

The panel can run any executable as the user of the host, so whoever gets in owns the server. What stands in the way:

- One password, kept as a PBKDF2-HMAC-SHA256 hash (120 000 rounds, random salt) in `$DATA_DIR/password`, mode 600. Set it with
  `unitship exec panel set-password`; this also ends every session.
- Sessions are a signed cookie, `HttpOnly`, `SameSite=Strict` and `Secure` (HTTPS only; `PANEL_INSECURE=1` turns `Secure`
  off). They last seven days. Logging out, or changing the password, ends **all** the sessions that exist: the server keeps the
  moment from which sessions are valid.
- After five wrong passwords in five minutes the login answers 429 for everybody (the failures are in a file, so this holds with
  several processes). A session that exists keeps working.
- Every request that changes something must come from the page: its `Origin` has to be the `Host` of the panel (a browser
  sends it), and it must be JSON or the upload of an executable, which a form of another site cannot send.
- App names, domains and variable names are checked before they reach the command line, which is run without a shell.
- `Content-Security-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options` and `Referrer-Policy` on every answer.
- The values of secrets are never sent to the page, only their names.

SHA-256, HMAC and PBKDF2 are written in Scala (Scala Native has no `MessageDigest`) and tested against the JVM's.

Not done, on purpose: several users, roles, two factor login, an audit of who did what (the activity of an app says what
happened, not who did it). Put the panel behind a VPN or an IP allow-list too if the server matters.

## The API

JSON, under `/api`; everything but `session` and `login` needs the session cookie.

| | |
|---|---|
| `GET /session`, `POST /login`, `POST /logout` | |
| `GET /status` | the host, the edge and a summary of every app (`unitship api status`) |
| `GET /apps/<app>` | releases, domains with certificates, names of the variables, latest events |
| `GET /apps/<app>/events?n=`, `/metrics?minutes=`, `/logs?n=` | |
| `GET /host/metrics?minutes=`, `/host/logs?n=` | |
| `POST /apps` `{name}`, `DELETE /apps/<app>` `{confirm}` | |
| `POST /apps/<app>/deploy` | the body is the executable (octet-stream) |
| `POST /apps/<app>/rollback` `{release?}`, `/start`, `/stop`, `/restart` | |
| `PUT /apps/<app>/env` `{set: {K: V}, unset: [K], restart}` | |
| `POST /apps/<app>/domains` `{add: [], remove: []}`, `POST /apps/<app>/tls` `{domains?}` | |

The answer of an action is `{ok, output}` (status 422 when it failed), with what `unitship` printed.

## Development

```bash
# The pure parts of the panel (crypto, passwords, sessions, JSON, HTTP helpers), on the JVM:
scala-cli test panel/src/Crypto.scala panel/src/Json.scala panel/src/Auth.scala panel/src/Http.scala \
  panel/test/CryptoTests.test.scala panel/test/PanelTests.test.scala

# The page: types, unit tests, build
cd web && npm ci && npm test && npm run build

# The panel against a real host (auth, CSRF, lockout, every action), with unitship deploying it:
panel/e2e.sh dist/unitship dist/panel

# A demo host with apps, a certificate and three hours of metrics, to work on the page with hot reload:
web/dev/demo.sh dist/unitship dist/panel      # prints the address; in another shell:
cd web && PANEL_URL=http://127.0.0.1:18080 npm run dev     # (the panel answers for panel.demo.local)

# The page in a real browser (Chrome), against the demo: clicks through the flows and checks the server, or takes screenshots
node web/dev/ui-e2e.mjs http://panel.demo.local:18080
node web/dev/screenshots.mjs http://panel.demo.local:18080 shots
```

`*.demo.local` has to resolve to your machine for the browser scripts (they map it themselves), and for the dev server add
`127.0.0.1 panel.demo.local` to `/etc/hosts` and open `http://panel.demo.local:5173`.
