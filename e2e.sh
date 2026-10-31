#!/usr/bin/env bash
# End to end test of unitship: the real edge FreeUnit and the local supervisor (no systemd needed), with small
# Python programs as apps.
#
# Usage: ./e2e.sh <unitship binary>
# Needs curl, openssl and python3. FreeUnit is downloaded the first time, or taken from SNUNIT_FREEUNIT_DIR.
# SUPERVISOR=systemd tests the systemd units instead (as an unprivileged user with a user manager running, see
# docker-e2e.sh), and HTTP and HTTPS are the ports of the edge.
set -euo pipefail

BIN="$(cd "$(dirname "${1:?usage: e2e.sh <unitship binary>}")" && pwd)/$(basename "$1")"
# Short path: unix socket paths are limited to ~100 characters.
export UNITSHIP_HOME="$(mktemp -d "${TMPDIR:-/tmp}/snh.XXXXXX")"
SUPERVISOR="${SUPERVISOR:-local}"
HTTP="${HTTP:-18080}"
HTTPS="${HTTPS:-18443}"
work="$(mktemp -d "${TMPDIR:-/tmp}/snh-work.XXXXXX")"

cleanup() {
  if [ "$SUPERVISOR" = systemd ]; then
    systemctl --user list-units --plain --no-legend 'unitship-*' | awk '{print $1}' | xargs -r systemctl --user disable --now 2>/dev/null || true
    rm -f "$HOME"/.config/systemd/user/unitship-*
    systemctl --user daemon-reload 2>/dev/null || true
  fi
  for pidfile in "$UNITSHIP_HOME"/run/*.pid; do
    [ -e "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
  done
  kill "${loop_pid:-}" 2>/dev/null || true
  rm -rf "$UNITSHIP_HOME" "$work"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
snh() { "$BIN" "$@"; }

# get <host> -> body through the edge: over HTTP, then over HTTPS once the host has a certificate
SECURE=0
status() { # <host> -> HTTP status
  if [ "$SECURE" = 1 ]; then
    curl -sk -o /dev/null -w '%{http_code}' --max-time 3 --resolve "$1:$HTTPS:127.0.0.1" "https://$1:$HTTPS/" || true
  else
    curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H "Host: $1" "http://127.0.0.1:$HTTP/" || true
  fi
}
get() {
  if [ "$SECURE" = 1 ]; then curl -fsSk --max-time 3 --resolve "$1:$HTTPS:127.0.0.1" "https://$1:$HTTPS/"
  else curl -fsS --max-time 3 -H "Host: $1" "http://127.0.0.1:$HTTP/"; fi
}
expect() { # <host> <expected body prefix>
  local body; body="$(get "$1")" || fail "GET $1 failed"
  [[ "$body" == "$2"* ]] || fail "GET $1: expected '$2', got '$body'"
  echo "ok: $1 -> $body"
}

make_app() { # <file> <version>
  cat > "$1" <<EOF
#!/usr/bin/env python3
import os, http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = ("$2 greeting=" + os.environ.get("GREETING", "") + " release=" + os.environ["UNITSHIP_RELEASE"]).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *args): pass
http.server.ThreadingHTTPServer((os.environ["BIND_ADDRESS"], int(os.environ["PORT"])), H).serve_forever()
EOF
  chmod +x "$1"
}

echo "== setup"
snh setup --supervisor "$SUPERVISOR" --http-port "$HTTP" --https-port "$HTTPS" --base-domain test.local --bin "$BIN"
sed -i.bak 's/^drain_seconds=.*/drain_seconds=1/' "$UNITSHIP_HOME/host.conf"

echo "== create and first deploy"
snh create web >/dev/null
make_app "$work/v1" v1
snh deploy web "$work/v1" >/dev/null
expect web.test.local "v1 greeting= release=1"

echo "== a deploy while requests are flowing loses none"
( while true; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H 'Host: web.test.local' "http://127.0.0.1:$HTTP/" || echo 000)"
    [ "$code" = 200 ] || echo "$code" >> "$work/bad"
    sleep 0.05
  done ) &
loop_pid=$!
make_app "$work/v2" v2
snh deploy web "$work/v2" >/dev/null
sleep 1.5
kill "$loop_pid"; loop_pid=
[ ! -s "$work/bad" ] || fail "requests failed during the deploy: $(sort "$work/bad" | uniq -c | tr '\n' ' ')"
expect web.test.local "v2 greeting= release=2"

echo "== config"
snh config web set GREETING="hello world" >/dev/null
expect web.test.local "v2 greeting=hello world release=3"
[ "$(snh config web)" = 'GREETING=hello world' ] || fail "config show"
snh config web unset GREETING >/dev/null
expect web.test.local "v2 greeting= release=4"
snh config web set UNITSHIP_RELEASE=9 2>/dev/null && fail "a reserved variable was accepted" || true

echo "== rollback"
snh rollback web 2 >/dev/null
expect web.test.local "v2 greeting= release=2"
snh rollback web 1 >/dev/null
expect web.test.local "v1 greeting= release=1"
snh releases web

echo "== a broken release is never switched to"
printf '#!/bin/sh\nexit 1\n' > "$work/bad-app"; chmod +x "$work/bad-app"
if snh deploy web "$work/bad-app" >/dev/null 2>&1; then fail "a crashing release was deployed"; fi
expect web.test.local "v1 greeting= release=1"
printf '#!/bin/sh\nexec sleep 100\n' > "$work/silent"; chmod +x "$work/silent"
snh config web set UNITSHIP_HEALTHCHECK_TIMEOUT=2 --no-restart >/dev/null
if snh deploy web "$work/silent" >/dev/null 2>&1; then fail "a release that listens nowhere was deployed"; fi
expect web.test.local "v1 greeting= release=1"

echo "== domains"
snh domains web add Extra.Example.com >/dev/null
expect extra.example.com "v1"
snh create other >/dev/null
snh domains other add extra.example.com 2>/dev/null && fail "a domain of another app was accepted" || true
snh domains web remove extra.example.com >/dev/null
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: extra.example.com' "http://127.0.0.1:$HTTP/")"
[ "$code" = 404 ] || fail "a removed domain answered $code"
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: other.test.local' "http://127.0.0.1:$HTTP/")"
[ "$code" = 503 ] || fail "an app without a release answered $code, expected 503"
echo "ok: 404 for unknown hosts, 503 for an app without a release"

echo "== git push deploys"
snh create pushed >/dev/null
snh domains pushed add pushed.example.com >/dev/null
snh git init pushed >/dev/null
snh config pushed set "UNITSHIP_BUILD_COMMAND=cp source app" --no-restart >/dev/null
mkdir "$work/src"
make_app "$work/src/source" v3
( cd "$work/src" && git init -q -b main . && git add . \
  && git -c user.name=t -c user.email=t@t commit -qm first \
  && git push -q "$UNITSHIP_HOME/git/pushed.git" main 2>&1 | sed 's/^/    /' )
expect pushed.example.com "v3 greeting= release=1"
# A push to another branch is not deployed.
( cd "$work/src" && git checkout -q -b other && git push -q "$UNITSHIP_HOME/git/pushed.git" other 2>&1 | sed 's/^/    /' )
expect pushed.example.com "v3 greeting= release=1"
# A failing build keeps the running release (git push itself still succeeds: the hook runs after the ref is updated).
snh config pushed set "UNITSHIP_BUILD_COMMAND=exit 3" --no-restart >/dev/null
out="$( cd "$work/src" && git checkout -q main && git -c user.name=t -c user.email=t@t commit -q --allow-empty -m second \
  && git push -q "$UNITSHIP_HOME/git/pushed.git" main 2>&1 )"
[[ "$out" == *"the build failed (exit code 3)"* ]] || fail "the build failure was not reported to the pusher: $out"
expect pushed.example.com "v3 greeting= release=1"

echo "== https with an imported certificate"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=web.test.local" \
  -addext "subjectAltName=DNS:web.test.local" -keyout "$work/key.pem" -out "$work/chain.pem" 2>/dev/null
snh tls import web.test.local "$work/chain.pem" "$work/key.pem" >/dev/null
body="$(curl -fsSk --max-time 3 --resolve "web.test.local:$HTTPS:127.0.0.1" "https://web.test.local:$HTTPS/")"
[[ "$body" == v1* ]] || fail "https: got '$body'"
location="$(curl -s -o /dev/null -w '%{redirect_url}' -H 'Host: web.test.local' "http://127.0.0.1:$HTTP/x?y=1")"
port=":$HTTPS"; [ "$HTTPS" != 443 ] || port=""
[ "$location" = "https://web.test.local$port/x?y=1" ] || fail "http did not redirect to https: '$location'"
echo "ok: https and redirect ($location)"
SECURE=1

echo "== ACME challenges are served over http"
mkdir -p "$UNITSHIP_HOME/edge/webroot/.well-known/acme-challenge"
echo token > "$UNITSHIP_HOME/edge/webroot/.well-known/acme-challenge/abc"
[ "$(curl -fsS -H 'Host: web.test.local' "http://127.0.0.1:$HTTP/.well-known/acme-challenge/abc")" = token ] || fail "challenge"
echo "ok"

echo "== stop, start, restart, ps"
snh stop web >/dev/null
code="$(status web.test.local)"
[ "$code" = 502 ] || fail "a stopped app answered $code, expected 502"
snh start web >/dev/null
sleep 1
expect web.test.local "v1"
snh restart web >/dev/null
sleep 1
expect web.test.local "v1"
snh ps

echo "== over ssh (a fake ssh runs the command here, as the remote shell would)"
mkdir "$work/bin"
cat > "$work/bin/ssh" <<'FAKESSH'
#!/bin/sh
# What the remote login shell has: not the UNITSHIP of the client.
unset UNITSHIP_HOST
shift
exec sh -c "$1"
FAKESSH
chmod +x "$work/bin/ssh"
remote() { PATH="$work/bin:$PATH" UNITSHIP_BIN="$BIN" "$BIN" "$@"; }
make_app "$work/v5" v5
remote --host me@server deploy web "$work/v5" >/dev/null
expect web.test.local "v5 greeting= release=5"
remote --host me@server config web set "MSG=it's a \"test\" \$HOME; echo no" >/dev/null
[ "$(snh config web | grep ^MSG=)" = "MSG=it's a \"test\" \$HOME; echo no" ] || fail "quoting over ssh: $(snh config web)"
[ "$(UNITSHIP_HOST=me@server remote ps | head -1 | cut -c1-3)" = APP ] || fail "UNITSHIP_HOST"
echo "ok: deploy through stdin, arguments with quotes and \$, UNITSHIP"

echo "== machine readable state (what the panel reads)"
snh api status | python3 -c '
import json, sys
d = json.load(sys.stdin)
assert d["edge"]["state"] == "running", d["edge"]
assert d["edge"]["status"]["requests"]["total"] > 0, d["edge"]
assert {"web", "pushed", "other"} <= {a["name"] for a in d["apps"]}, d["apps"]
web = next(a for a in d["apps"] if a["name"] == "web")
assert web["state"] == "running" and web["https"] is True and web["port"], web
assert "diskTotalBytes" in d["host"], d["host"]
print("ok: api status")'
snh api app web > "$work/web.json"
python3 - "$work/web.json" <<'PY'
import json, os, sys
text = open(sys.argv[1]).read()
d = json.loads(text)
assert d["active"] and d["releases"][0]["id"] >= d["active"], d
assert sum(1 for r in d["releases"] if r["active"]) == 1, d["releases"]
assert all(r["createdAt"].endswith("Z") for r in d["releases"]), d["releases"]
assert d["domains"][0]["name"] == "web.test.local" and d["domains"][0]["https"] is True
assert d["domains"][0]["certificateExpires"].startswith("20"), d["domains"]
assert d["env"] == ["MSG", "UNITSHIP_HEALTHCHECK_TIMEOUT"], d["env"]
assert "echo no" not in text, "a value of the environment leaked"
kinds = [e["type"] for e in d["events"]]
for kind in ["create", "deploy", "config", "rollback", "deploy_failed", "domain", "tls"]:
    assert kind in kinds, (kind, kinds)
assert all(e["at"].endswith("Z") for e in d["events"])
active = next(r for r in d["releases"] if r["active"])
assert active["state"] == "running" and active["memoryBytes"] and active["memoryBytes"] > 0, active
if os.environ.get("SUPERVISOR") == "systemd":  # only systemd accounts the CPU time
    assert active["cpuNanos"] is not None and active["cpuNanos"] > 0, active
print("ok: api app, events:", " ".join(kinds[-6:]))
PY
snh api events web -n 2 | python3 -c 'import json, sys; assert len(json.load(sys.stdin)) == 2; print("ok: api events")'
snh api app nope 2>/dev/null && fail "api app accepted an unknown app" || true

echo "== the edge access log (what the panel graphs)"
get web.test.local >/dev/null
python3 - "$UNITSHIP_HOME/edge/access.log" <<'PY'
import sys
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1])]
good = [r for r in rows if len(r) == 4 and r[0] == "web.test.local" and r[1] == "200" and float(r[2]) >= 0 and int(r[3]) > 0]
assert good, rows[-5:]
print("ok: %d lines, e.g. %s" % (len(rows), good[-1]))
PY

echo "== metrics (what the panel graphs)"
snh collect
for _ in 1 2 3 4 5; do get web.test.local >/dev/null; done
sleep 0.3
snh collect
snh api metrics web -n 10 > "$work/metrics.json"
python3 - "$work/metrics.json" <<'PY'
import json, sys
points = json.load(open(sys.argv[1]))
assert len(points) >= 2, points
assert [p["ms"] for p in points] == sorted(p["ms"] for p in points)
assert all(p["t"].endswith("Z") and p["seconds"] > 0 for p in points), points
last = points[-1]
assert last["requests"] >= 5 and last["s2"] >= 5 and last["bytes"] > 0, last
assert last["avgMs"] >= 0 and last["p95Ms"] >= last["avgMs"] - 0.01, last
assert last["memoryBytes"] and last["memoryBytes"] > 0, last
print("ok: %d points, last: %d requests, avg %.2f ms" % (len(points), last["requests"], last["avgMs"]))
PY
snh collect
snh api metrics web -n 10 | python3 -c '
import json, sys
points = json.load(sys.stdin)
assert points[-1]["requests"] == 0, points[-1]  # the same lines are not counted twice
print("ok: a quiet period counts no request")'
snh api metrics _host -n 10 | python3 -c 'import json, sys; assert len(json.load(sys.stdin)) >= 2; print("ok: host points")'
snh api metrics nope 2>/dev/null && fail "metrics of an unknown app" || true

echo "== maintenance rotates the logs and the edge keeps logging"
UNITSHIP_LOG_ROTATE_BYTES=1 snh maintenance >/dev/null
[ -s "$UNITSHIP_HOME/edge/access.log.1" ] || fail "the access log was not rotated"
sleep 0.5
get web.test.local >/dev/null
sleep 0.5
grep -q "web.test.local" "$UNITSHIP_HOME/edge/access.log" || fail "the edge does not write to the new access log"
echo "ok"

echo "== logs"
snh logs web -n 5 >/dev/null || fail "logs"

echo "== destroy"
snh destroy web 2>/dev/null && fail "destroy without --yes"
snh destroy web --yes >/dev/null
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: web.test.local' "http://127.0.0.1:$HTTP/")"
[ "$code" = 404 ] || fail "a destroyed app answered $code (http)"
[ ! -e "$UNITSHIP_HOME/apps/web" ] || fail "the app directory is still there"

echo "ALL OK"
