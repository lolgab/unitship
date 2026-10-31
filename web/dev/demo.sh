#!/usr/bin/env bash
# A demo of the panel with apps, traffic history and a certificate, to develop and test the page against.
#
# Usage: web/dev/demo.sh <unitship binary> <panel binary>
# Prints the address and the password, and runs until Ctrl-C. With `npm run dev` in web/ and PANEL_URL set to the address it
# prints, the page is served with hot reload.
set -euo pipefail

UNITSHIP="$(cd "$(dirname "${1:?usage: demo.sh <unitship binary> <panel binary>}")" && pwd)/$(basename "$1")"
PANEL="$(cd "$(dirname "${2:?usage: demo.sh <unitship binary> <panel binary>}")" && pwd)/$(basename "$2")"
export UNITSHIP_HOME="$(mktemp -d "${TMPDIR:-/tmp}/snh.XXXXXX")"
work="$(mktemp -d "${TMPDIR:-/tmp}/snh-demo.XXXXXX")"
HTTP="${HTTP:-18080}"
PASSWORD="${PASSWORD:-demo-password}"

cleanup() {
  for pidfile in "$UNITSHIP_HOME"/run/*.pid; do
    [ -e "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
  done
  rm -rf "$UNITSHIP_HOME" "$work"
}
trap cleanup EXIT
ship() { "$UNITSHIP" "$@"; }

cat > "$work/app.py" <<'PY'
#!/usr/bin/env python3
import os, http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = ("hello from " + os.environ["UNITSHIP_APP"] + " " + os.environ.get("GREETING", "")).encode()
        self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def log_request(self, code="-", size="-"):  # (the standard one logs to stderr, through log_message, only up to Python 3.14)
        print(self.requestline, code, flush=True)
    def log_message(self, *args):
        pass
http.server.ThreadingHTTPServer((os.environ["BIND_ADDRESS"], int(os.environ["PORT"])), H).serve_forever()
PY
chmod +x "$work/app.py"

ship setup --supervisor local --http-port "$HTTP" --https-port 18443 --base-domain demo.local --bin "$UNITSHIP" >/dev/null 2>&1
sed -i.bak 's/^drain_seconds=.*/drain_seconds=1/' "$UNITSHIP_HOME/host.conf"

# The panel itself.
ship create panel >/dev/null
ship config panel set PANEL_INSECURE=1 --no-restart >/dev/null
ship deploy panel "$PANEL" >/dev/null
printf '%s\n%s\n' "$PASSWORD" "$PASSWORD" | ship exec panel set-password >/dev/null 2>&1

# Apps with a history.
for app in web api blog worker; do
  ship create "$app" >/dev/null
  ship deploy "$app" "$work/app.py" >/dev/null
done
ship config web set GREETING=hello DATABASE_URL=postgres://secret API_KEY=secret >/dev/null
ship config api set DATABASE_URL=postgres://secret >/dev/null
ship deploy web "$work/app.py" >/dev/null
ship domains web add www.example.com >/dev/null
ship stop worker >/dev/null

openssl req -x509 -newkey rsa:2048 -nodes -days 60 -subj "/CN=web.demo.local" -addext "subjectAltName=DNS:web.demo.local" \
  -keyout "$work/key.pem" -out "$work/chain.pem" 2>/dev/null
ship tls import web.demo.local "$work/chain.pem" "$work/key.pem" >/dev/null

# Three hours of metrics, one point a minute.
python3 - "$UNITSHIP_HOME/metrics" <<'PY'
import json, math, random, sys, time
random.seed(7)
directory = sys.argv[1]
now = int(time.time() * 1000)
profiles = {  # requests per minute, share of errors, latency in ms, memory in MB
    "web": (420, 0.004, 14, 61),
    "api": (150, 0.012, 38, 94),
    "blog": (35, 0.0, 9, 28),
    "panel": (6, 0.0, 6, 31),
    "worker": (0, 0.0, 0, 0),
}
def iso(ms):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ms / 1000))
for app, (rate, errors, latency, memory) in profiles.items():
    lines = []
    cpu_total = 0.0
    for i in range(180, 0, -1):
        ms = now - i * 60000
        wave = 1 + 0.35 * math.sin(i / 17) + random.uniform(-0.15, 0.15)
        n = max(0, int(rate * wave))
        e5 = sum(random.random() < errors * (6 if 95 < i < 110 else 1) for _ in range(min(n, 400)))
        e4 = int(n * random.uniform(0.0, 0.03)) if n else 0
        e3 = int(n * 0.02) if n else 0
        s2 = max(0, n - e3 - e4 - e5)
        avg = latency * (1 + 0.4 * math.sin(i / 11)) * (3 if 95 < i < 110 else 1) if n else 0
        cpu_total += 0
        lines.append(json.dumps({
            "ms": ms, "t": iso(ms), "seconds": 60.0, "requests": n, "s2": s2, "s3": e3, "s4": e4, "s5": e5,
            "avgMs": round(avg, 2), "p95Ms": round(avg * random.uniform(2.2, 3.4), 2), "bytes": n * 900,
            "memoryBytes": int(memory * 1048576 * (1 + 0.02 * math.sin(i / 7))) if memory else None,
            "cpuPct": round(max(0, n / 50 * random.uniform(0.7, 1.3)), 2) if memory else None,
        }, separators=(",", ":")))
    open(f"{directory}/{app}.jsonl", "w").write("\n".join(lines) + "\n")
host = []
for i in range(180, 0, -1):
    ms = now - i * 60000
    host.append(json.dumps({"ms": ms, "t": iso(ms), "load1": round(0.6 + 0.5 * math.sin(i / 23) + random.uniform(0, 0.2), 2),
        "memoryAvailableBytes": int((5.2 - 0.5 * math.sin(i / 31)) * 1073741824), "memoryTotalBytes": 8 * 1073741824}, separators=(",", ":")))
open(f"{directory}/_host.jsonl", "w").write("\n".join(host) + "\n")
PY
# Some real requests, so that the logs have lines.
for _ in $(seq 1 20); do
  curl -sk -o /dev/null --resolve "web.demo.local:18443:127.0.0.1" "https://web.demo.local:18443/"
  curl -s -o /dev/null -H 'Host: api.demo.local' "http://127.0.0.1:$HTTP/"
done

cat <<INFO
Demo ready:
  panel     http://panel.demo.local:$HTTP  (send the Host header, or add it to /etc/hosts; the dev server proxies to http://127.0.0.1:$HTTP)
  password  $PASSWORD
  apps      web api blog worker (stopped)
Ctrl-C to stop.
INFO
# The demo panel answers only for panel.demo.local: this is the address to give to PANEL_URL.
touch "$work/ready"
[ -z "${DEMO_READY_FILE:-}" ] || echo "ready" > "$DEMO_READY_FILE"
while true; do sleep 3600; done
