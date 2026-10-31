# unitship

[![ci](https://github.com/lolgab/unitship/actions/workflows/ci.yml/badge.svg)](https://github.com/lolgab/unitship/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/lolgab/unitship)](https://github.com/lolgab/unitship/releases)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

A small self-hosted platform for [SNUnit](https://github.com/lolgab/snunit) applications, in the spirit of
[Dokku](https://dokku.com/) but with no Docker: `git push` or `unitship deploy` puts a new release live with no
downtime, on one server, with HTTPS.

```bash
unitship --host me@my-server create blog
unitship --host me@my-server domains blog add blog.example.com
unitship --host me@my-server deploy blog ./blog          # a single executable built with `snunit package`
unitship --host me@my-server tls blog                    # Let's Encrypt
```

Docs: <https://lolgab.github.io/unitship/> · [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md) · [Security](SECURITY.md)

## Install

```bash
curl -fsSL https://lolgab.github.io/unitship/install.sh | bash     # Linux x86_64, macOS arm64
unitship --version
```

Or download a binary from the [releases](https://github.com/lolgab/unitship/releases), or build it, see [Development](#development).

## How it works

```
                      :80 / :443
 client ───────────▶  edge FreeUnit  ──(by Host header)──▶  127.0.0.1:20001   app "blog",  release 7
                      TLS, redirects                         127.0.0.1:20002   app "shop",  release 3
                      ACME challenges
```

- An SNUnit app is **one executable that embeds its own `unitd`**. Every release is a process (supervised by systemd) that listens on
  `127.0.0.1:<port>`. The host picks the port and gives it to the app in `$PORT`.
- One more FreeUnit, the **edge**, listens on 80 and 443. The host configures it through its control API with a route per
  domain that proxies to the port of the active release, and loads the certificates into it.
- A deploy starts the new release on a new port, waits until it answers, points the edge to it (an atomic FreeUnit
  reconfiguration, so no request is lost), waits a few seconds for the running requests, then stops the old release.
  If the new release does not start, nothing changes and the old one keeps serving. `rollback` does the same with an
  older release.
- Everything runs as one unprivileged user with `systemctl --user`: no root, no daemon of ours. The state is plain files in
  `~/.unitship`, see [Layout](#layout).
- Commands run on the server. With `--host user@server` (or `UNITSHIP_HOST`) the same command line runs there over `ssh`,
  and `deploy` sends the executable through ssh. There is no other protocol and no other credential.

## Your app

It must listen on `$BIND_ADDRESS:$PORT` (`127.0.0.1` and a port chosen by the host), which is not the default of SNUnit (`*:8080`):

```scala
//> using dep com.github.lolgab::snunit::0.13.0
import snunit.*
import snunit.config.*

@main
def run =
  val address = s"${sys.env.getOrElse("BIND_ADDRESS", "*")}:${sys.env.getOrElse("PORT", "8080")}"
  SyncServerBuilder
    .setConfig(UnitConfig(listeners = Seq(Listener(address))))
    .setRequestHandler(req => req.send(StatusCode.OK, "Hello world!\n", Headers.empty))
    .build()
    .listen()
```

The same code runs unchanged with `snunit run` on your machine. The host sets these variables, besides the ones you set with
`config`:

| Variable | |
|---|---|
| `PORT`, `BIND_ADDRESS` | where to listen |
| `DATA_DIR` | a directory that is kept across releases (`~/.unitship/apps/<app>/data`) |
| `UNITSHIP_APP`, `UNITSHIP_RELEASE` | the name of the app and the number of the release |

The FreeUnit of the edge speaks HTTP/1.1 to the app and does not add `X-Forwarded-*` headers: the client address is not
visible to the app.

## The panel

[`panel/`](panel/README.md) is a web page for the host, as an SNUnit app that unitship runs like any other: apps and their state,
releases with rollbacks, deploys by upload, secrets, domains and certificates, request, latency, memory and CPU charts, live logs
and the activity of every app. It only uses the `unitship` command line tool, so the two always agree.

## Setting up a server

Any Linux with systemd (Debian, Ubuntu, Fedora...), `curl` and `ssh`. As root, once:

```bash
useradd -m -s /bin/bash unitship
loginctl enable-linger unitship                            # the user's services run without a login session
echo 'net.ipv4.ip_unprivileged_port_start=80' > /etc/sysctl.d/50-unitship.conf && sysctl --system   # ports 80 and 443
```

Then, as that user (put your ssh key in `~unitship/.ssh/authorized_keys`), with `unitship` for the server's
architecture in `~/.local/bin` (build it with `scalino package vendor src -o unitship --native-target-triple x86_64-unknown-linux-musl`):

```bash
unitship setup --email you@example.com --base-domain apps.example.com
```

`setup` downloads the FreeUnit of the edge, starts it, installs the daily renewal of the certificates and tells you what
is still missing (it checks lingering, the ports, `curl` and `lego`). It can run again to change a setting.
`--base-domain` gives every new app the domain `<app>.apps.example.com`: point `*.apps.example.com` to the server.

For HTTPS install [lego](https://go-acme.github.io/lego/) (version 4 or 5) on the server. Let's Encrypt has to reach the
server on port 80.

## Commands

| | |
|---|---|
| `create <app>` / `destroy <app> --yes` | |
| `deploy <app> <executable>` | new release, live with no downtime |
| `releases <app>`, `rollback <app> [<release>]` | the last 5 releases are kept |
| `config <app>` / `config <app> set K=V... [--no-restart]` / `config <app> unset K...` | the environment. A change is a new release (also zero downtime) |
| `domains <app> [add\|remove <domain>...]` | |
| `tls <app> [<domain>...]` | get certificates, `tls renew` renews those that expire in less than 30 days (a systemd timer runs it every day; the CA may say it is too early), `tls renew --force` renews now, `tls import <domain> <chain.pem> <key.pem>` uses your own |
| `ps` | apps, release, port, state, domains |
| `exec <app> <args>...` | runs the executable of the app with its environment, for a task (`exec panel set-password`) |
| `collect`, `maintenance`, `api status\|app\|events\|metrics` | what the timers and the panel run |
| `start`, `stop`, `restart <app>` | |
| `logs <app> [-n 100] [-f]`, `logs --edge` | `journalctl` of the app, the log file of the edge |

Settings that are environment variables of the app: `UNITSHIP_HEALTHCHECK_PATH` (a deploy waits for a status below 500 on this
path, by default for the port to accept connections), `UNITSHIP_HEALTHCHECK_TIMEOUT` (seconds, 30).

## Deploying with git push

```bash
unitship --host me@my-server git init blog        # prints the remote
git remote add unitship me@my-server:/home/me/.unitship/git/blog.git
git push unitship main
```

The server builds the pushed commit with `snunit package . -o app` and deploys it. The build output comes back with the push.
It needs [`snunit`](https://github.com/lolgab/snunit#getting-started) and [scalino](https://github.com/lolgab/scalino) on the server, in the
`PATH` of ssh sessions, and enough memory to compile and link a Scala Native program. Without them, build on your machine
or in CI and use `deploy`. Settings, as variables of the app:

| | |
|---|---|
| `UNITSHIP_BUILD_COMMAND` | any command that produces `./app` (default `snunit package <UNITSHIP_BUILD_ARGS> -o app`) |
| `UNITSHIP_BUILD_ARGS` | arguments of `snunit package`, `.` by default |
| `UNITSHIP_DEPLOY_BRANCH` | the branch that is deployed, `main` by default (other branches are ignored) |

A failed build is shown to the one who pushes (`git push` itself still succeeds, as git runs the hook after updating the branch)
and the running release is not touched.

## Layout

```
~/.unitship/
  host.conf                 ports, base domain, ACME email and server, supervisor
  bin/unitd                 FreeUnit of the edge
  edge/                     its state, control socket, log and the ACME webroot
  certs/<domain>.pem        certificate chain and key, loaded in the edge
  acme/                     what lego keeps (account, certificates)
  git/<app>.git
  apps/<app>/app.conf       domains, active and previous release
  apps/<app>/env            what `config set` wrote
  apps/<app>/data/
  apps/<app>/releases/<n>/  app (the executable), env, release.conf (its port)
```

The units are `~/.config/systemd/user/unitship-edge.service` and `unitship-<app>-<release>.service`. They are enabled, so the edge and
the active releases come back after a reboot.

## Limits

- **One server.** There is no scheduler and no multi-node anything. That is the point.
- **Isolation is that of a Unix user.** All the apps run as the same user and can read each other's files. Don't host code that you
  don't trust.
- **No databases or other services.** Run Postgres yourself (or use a managed one) and put the URL in `config`.
- **Logs of the edge are not rotated** (`edge/unit.log`): use `logrotate` with `copytruncate`.
- **ACME needs port 80** and the HTTP-01 challenge: no wildcard certificates.
- **macOS** works (`--supervisor local`, picked automatically without systemd) for trying it, with no restart on crash and
  nothing at boot. FreeUnit on macOS can't store a certificate uploaded to its API, so loading a certificate restarts the edge there.
- The edge and the apps are FreeUnit: HTTP/1.1 only, no gRPC.

## Development

```bash
# The pure part (configuration of the edge, systemd units, names, releases...), on the JVM:
scala-cli test src/Core.scala test/CoreTests.test.scala

# Build:
scalino package vendor src -o unitship

# End to end, with the real edge FreeUnit and small Python programs as apps (local supervisor):
./e2e.sh ./unitship

# The same under real systemd, as an unprivileged user on ports 80 and 443, in a container
# (needs a unitship for Linux of the architecture of docker):
./docker-e2e.sh ./unitship-linux

# Let's Encrypt against Pebble (the test ACME server), with lego 4 and 5, in containers:
./docker-acme-e2e.sh ./unitship-linux
```

`e2e.sh` checks that a deploy under continuous requests loses none, rollbacks, a release that crashes or never listens is not
switched to, domains, 404 and 503, HTTPS with a redirect, `git push`, `ssh` (with a fake `ssh` that runs the command locally) and
`destroy`.
