#!/usr/bin/env bash
# End to end test of the panel: unitship deploys it like any app, and the test talks to it through the edge, with the real
# unitship behind it.
#
# Usage: panel/e2e.sh <unitship binary> <panel binary>
# Needs curl, python3 and FreeUnit (downloaded the first time, or SNUNIT_FREEUNIT_DIR).
set -euo pipefail

UNITSHIP="$(cd "$(dirname "${1:?usage: e2e.sh <unitship binary> <panel binary>}")" && pwd)/$(basename "$1")"
PANEL="$(cd "$(dirname "${2:?usage: e2e.sh <unitship binary> <panel binary>}")" && pwd)/$(basename "$2")"
export UNITSHIP_HOME="$(mktemp -d "${TMPDIR:-/tmp}/snh.XXXXXX")"
work="$(mktemp -d "${TMPDIR:-/tmp}/snh-work.XXXXXX")"
HTTP="${HTTP:-18080}"

cleanup() {
  for pidfile in "$UNITSHIP_HOME"/run/*.pid; do
    [ -e "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
  done
  rm -rf "$UNITSHIP_HOME" "$work"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
ship() { "$UNITSHIP" "$@"; }

HOST=panel.test.local
URL="http://127.0.0.1:$HTTP"
# call <method> <path> [curl args...] -> body, with the status in $status
call() {
  local method="$1" path="$2"; shift 2
  curl -s -o "$work/body" -w '%{http_code}' --max-time 120 -X "$method" -H "Host: $HOST" "$URL$path" "$@" > "$work/status" || true
  status="$(cat "$work/status")"
  body="$(cat "$work/body")"
}
# api <method> <path> [json] -> as a logged in browser of the page would
api() {
  local method="$1" path="$2" data="${3:-}"
  if [ -n "$data" ]; then
    call "$method" "$path" -b "$work/cookies" -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data "$data"
  else
    call "$method" "$path" -b "$work/cookies" -H "Origin: http://$HOST"
  fi
}
expect_status() { [ "$status" = "$1" ] || fail "$2: expected $1, got $status: $body"; echo "ok: $2 ($status)"; }
json() { python3 -c "import json,sys; d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))" "$body" "$1"; }

make_app() { # <file> <version>
  cat > "$1" <<PY
#!/usr/bin/env python3
import os, http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = ("$2 " + os.environ.get("GREETING", "")).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_message(self, *args): pass
http.server.ThreadingHTTPServer((os.environ["BIND_ADDRESS"], int(os.environ["PORT"])), H).serve_forever()
PY
  chmod +x "$1"
}

echo "== deploy the panel with unitship"
ship setup --supervisor local --http-port "$HTTP" --https-port 18443 --base-domain test.local --bin "$UNITSHIP" >/dev/null 2>&1
sed -i.bak 's/^drain_seconds=.*/drain_seconds=1/' "$UNITSHIP_HOME/host.conf"
ship create panel >/dev/null
ship config panel set PANEL_INSECURE=1 --no-restart >/dev/null
ship deploy panel "$PANEL" >/dev/null
echo "ok: deployed"

echo "== before a password is set"
call GET /api/session
expect_status 200 "session"
[ "$(json 'd["configured"]')" = False ] || fail "configured before set-password: $body"
call POST /api/login -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"password":"x"}'
expect_status 503 "login without a password"

echo "== set the password with unitship exec"
printf 'correct-horse\ncorrect-horse\n' | ship exec panel set-password 2>&1 | grep -q "password saved" || fail "set-password"
printf 'short\nshort\n' | ship exec panel set-password >/dev/null 2>&1 && fail "a short password was accepted" || true
printf 'correct-horse\nother-horse\n' | ship exec panel set-password >/dev/null 2>&1 && fail "different passwords were accepted" || true
ls -l "$UNITSHIP_HOME/apps/panel/data/password" | grep -q -- '-rw-------' || fail "the password file is not private"
echo "ok"

echo "== nothing without a login"
call GET /api/session;  [ "$(json 'd["configured"]')" = True ] || fail "configured: $body"
call GET /api/status;   expect_status 401 "status"
call GET /api/apps/panel; expect_status 401 "app"
call POST /api/apps -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"name":"x"}'; expect_status 401 "create"

echo "== login"
call POST /api/login -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"password":"wrong"}'
expect_status 401 "wrong password"
call POST /api/login -H 'Origin: https://evil.example' -H 'Content-Type: application/json' --data '{"password":"correct-horse"}'
expect_status 403 "login from another origin"
call POST /api/login -H 'Content-Type: application/json' --data '{"password":"correct-horse"}'
expect_status 403 "login without an origin"
call POST /api/login -H "Origin: http://$HOST" --data '{"password":"correct-horse"}'
expect_status 400 "login without a json content type"
curl -s -o /dev/null -D "$work/headers" -c "$work/cookies" -X POST -H "Host: $HOST" -H "Origin: http://$HOST" -H 'Content-Type: application/json' \
  --data '{"password":"correct-horse"}' "$URL/api/login"
grep -qi '^HTTP/1.1 200' "$work/headers" || fail "login: $(head -1 "$work/headers")"
grep -i '^set-cookie:' "$work/headers" | grep -qi 'HttpOnly' || fail "the cookie is not HttpOnly"
grep -i '^set-cookie:' "$work/headers" | grep -qi 'SameSite=Strict' || fail "the cookie is not SameSite=Strict"
grep -qi '^x-frame-options: DENY' "$work/headers" || fail "no X-Frame-Options"
grep -qi '^content-security-policy:' "$work/headers" || fail "no Content-Security-Policy"
echo "ok: logged in, with a HttpOnly SameSite=Strict cookie and security headers"

echo "== reading"
api GET /api/session;  [ "$(json 'd["authenticated"]')" = True ] || fail "session after login: $body"
api GET /api/status;   expect_status 200 "status"
[ "$(json '[a["name"] for a in d["apps"]]')" = "['panel']" ] || fail "apps: $body"
[ "$(json 'd["edge"]["state"]')" = running ] || fail "edge: $body"
api GET /api/apps/panel; expect_status 200 "app"
[ "$(json 'd["env"]')" = "['PANEL_INSECURE']" ] || fail "env names: $body"
api GET /api/apps/panel/events; expect_status 200 "events"
api GET '/api/apps/panel/metrics?minutes=30'; expect_status 200 "metrics"
api GET '/api/host/metrics?minutes=30'; expect_status 200 "host metrics"
api GET '/api/apps/panel/logs?n=20'; expect_status 200 "logs"
api GET /api/host/logs; expect_status 200 "edge logs"
api GET /api/apps/nope; expect_status 404 "unknown app"
api GET /api/apps/-x; expect_status 404 "invalid app name"
api GET /api/nothing; expect_status 404 "unknown route"
call GET /api/status -b "$work/cookies" -H 'Cookie: unitship_session=forged.token'; expect_status 401 "forged cookie"
tampered="$(sed 's/.*unitship_session\t//' "$work/cookies" | tail -1)"
call GET /api/status -H "Cookie: unitship_session=${tampered}x"; expect_status 401 "tampered cookie"

echo "== writing needs the page of the panel"
call POST /api/apps -b "$work/cookies" -H 'Origin: https://evil.example' -H 'Content-Type: application/json' --data '{"name":"blog"}'
expect_status 403 "create from another origin"
call POST /api/apps -b "$work/cookies" -H 'Content-Type: application/json' --data '{"name":"blog"}'
expect_status 403 "create without an origin"
api POST /api/apps '{"name":"Bad Name"}'; expect_status 400 "invalid name"
api POST /api/apps '{"name":"--help"}'; expect_status 400 "a name that looks like an option"

echo "== create, deploy an uploaded executable, configure, roll back, destroy"
api POST /api/apps '{"name":"blog"}'; expect_status 200 "create"
make_app "$work/v1" v1
call POST /api/apps/blog/deploy -b "$work/cookies" -H "Origin: http://$HOST" -H 'Content-Type: application/octet-stream' --data-binary "@$work/v1"
expect_status 200 "deploy"
[ "$(json 'd["ok"]')" = True ] || fail "deploy: $body"
[ "$(curl -s -H 'Host: blog.test.local' "$URL/")" = "v1 " ] || fail "the uploaded app does not answer"
make_app "$work/v2" v2
call POST /api/apps/blog/deploy -b "$work/cookies" -H "Origin: http://$HOST" -H 'Content-Type: application/octet-stream' --data-binary "@$work/v2"
expect_status 200 "second deploy"
[ "$(curl -s -H 'Host: blog.test.local' "$URL/")" = "v2 " ] || fail "v2 does not answer"
printf '#!/bin/sh\nexit 1\n' > "$work/broken"; chmod +x "$work/broken"
call POST /api/apps/blog/deploy -b "$work/cookies" -H "Origin: http://$HOST" -H 'Content-Type: application/octet-stream' --data-binary "@$work/broken"
expect_status 422 "a broken executable"
[ "$(json 'd["ok"]')" = False ] || fail "broken deploy: $body"
[ "$(curl -s -H 'Host: blog.test.local' "$URL/")" = "v2 " ] || fail "the broken release replaced v2"
call POST /api/apps/blog/deploy -b "$work/cookies" -H "Origin: http://$HOST" -H 'Content-Type: application/octet-stream'
expect_status 400 "a deploy without a body"

api PUT /api/apps/blog/env '{"set":{"GREETING":"hello world","OTHER":"a=b"},"unset":[]}'; expect_status 200 "set env"
[ "$(curl -s -H 'Host: blog.test.local' "$URL/")" = "v2 hello world" ] || fail "the environment did not reach the app"
api GET /api/apps/blog; [ "$(json 'd["env"]')" = "['GREETING', 'OTHER']" ] || fail "env: $body"
case "$body" in *"hello world"*) fail "the value of a variable is in the answer";; esac
api PUT /api/apps/blog/env '{"set":{"BAD-KEY":"x"}}'; expect_status 400 "invalid variable name"
api PUT /api/apps/blog/env '{"set":{"PORT":"1"}}'; expect_status 422 "a reserved variable"
api PUT /api/apps/blog/env '{"unset":["OTHER"],"set":{"X":"1"}}'; expect_status 200 "set and unset together"
api GET /api/apps/blog; [ "$(json 'd["env"]')" = "['GREETING', 'X']" ] || fail "env after: $body"

api POST /api/apps/blog/domains '{"add":["Extra.Example.com"]}'; expect_status 200 "add a domain"
[ "$(curl -s -H 'Host: extra.example.com' "$URL/")" = "v2 hello world" ] || fail "the new domain does not answer"
api POST /api/apps/blog/domains '{"add":["--email"]}'; expect_status 400 "a domain that looks like an option"
api POST /api/apps/blog/domains '{"remove":["extra.example.com"]}'; expect_status 200 "remove a domain"

api GET /api/apps/blog; active="$(json 'd["active"]')"
api POST /api/apps/blog/rollback '{"release":2}'; expect_status 200 "rollback"
[ "$(curl -s -H 'Host: blog.test.local' "$URL/")" = "v2 " ] || [ "$(curl -s -H 'Host: blog.test.local' "$URL/")" = "v2 hello world" ] || fail "rollback"
api POST /api/apps/blog/rollback '{"release":999}'; expect_status 422 "rollback to a release that does not exist"
api POST /api/apps/blog/stop; expect_status 200 "stop"
[ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: blog.test.local' "$URL/")" = 502 ] || fail "a stopped app answers"
api POST /api/apps/blog/start; expect_status 200 "start"
sleep 1
api POST /api/apps/blog/restart; expect_status 200 "restart"
api GET /api/apps/blog/events
[ "$(json 'sorted(set(e["type"] for e in d))')" = "['config', 'create', 'deploy', 'deploy_failed', 'domain', 'restart', 'rollback', 'start', 'stop']" ] || fail "events: $body"
echo "ok: the history has every kind of event"

api DELETE /api/apps/blog '{"confirm":"nope"}'; expect_status 400 "destroy without the right confirmation"
api DELETE /api/apps/blog '{"confirm":"blog"}'; expect_status 200 "destroy"
api GET /api/apps/blog; expect_status 404 "the destroyed app"

echo "== logout"
call POST /api/logout -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{}'
api GET /api/status; expect_status 200 "a logout without a session does not end the session of somebody else"
cp "$work/cookies" "$work/old-cookies"
curl -s -o /dev/null -D "$work/headers" -b "$work/cookies" -X POST -H "Host: $HOST" -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{}' "$URL/api/logout"
grep -i '^set-cookie:' "$work/headers" | grep -qi 'Max-Age=0' || fail "logout does not clear the cookie"
call GET /api/status -b "$work/old-cookies"; expect_status 401 "a copy of the cookie after the logout"
# A new login works right away (the revocation is of the sessions issued before).
curl -s -o /dev/null -c "$work/cookies" -X POST -H "Host: $HOST" -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"password":"correct-horse"}' "$URL/api/login"
api GET /api/status; expect_status 200 "a new session after the logout"

echo "== too many wrong passwords lock the login (for everybody)"
for _ in 1 2 3 4 5; do
  call POST /api/login -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"password":"nope"}'
done
call POST /api/login -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"password":"correct-horse"}'
expect_status 429 "the right password while locked"
curl -s -D - -o /dev/null -X POST -H "Host: $HOST" -H "Origin: http://$HOST" -H 'Content-Type: application/json' --data '{"password":"correct-horse"}' "$URL/api/login" | grep -qi '^retry-after:' || fail "no Retry-After"
# Whoever is logged in stays logged in.
api GET /api/status; expect_status 200 "the session of the administrator during the lockout"

echo "ALL OK"
