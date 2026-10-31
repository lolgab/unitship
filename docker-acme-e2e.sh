#!/usr/bin/env bash
# Tests `unitship tls` (Let's Encrypt) against Pebble, the ACME test server of Let's Encrypt, for the versions of
# lego it supports. Pebble reaches the app container through a mock DNS server and validates the HTTP-01 challenge
# on port 5002, where the edge listens.
#
# Usage: ./docker-acme-e2e.sh <unitship binary for linux> [<lego version>...]
# Needs docker. The default lego versions are v4.26.0 and v5.5.2.
set -euo pipefail

BIN="$(cd "$(dirname "${1:?usage: docker-acme-e2e.sh <unitship binary for linux> [<lego version>...]}")" && pwd)/$(basename "$1")"
shift
versions=("$@")
[ ${#versions[@]} -gt 0 ] || versions=(v4.26.0 v5.5.2)

id="$$"
net="unitship-acme-$id"
dist="$(mktemp -d "${TMPDIR:-/tmp}/snh-acme.XXXXXX")"
cleanup() {
  docker rm -f "app-$id" "chall-$id" "pebble-$id" >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  rm -rf "$dist"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

cp "$BIN" "$dist/unitship"; chmod 755 "$dist/unitship"

docker build -q -t unitship-acme --build-arg "LEGO_VERSIONS=${versions[*]}" - <<'DOCKERFILE' >/dev/null
FROM debian:stable-slim
ARG LEGO_VERSIONS
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates python3 openssl tar procps \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -s /bin/bash unitship \
 && arch="$(dpkg --print-architecture)" \
 && for v in $LEGO_VERSIONS; do \
      mkdir -p "/opt/lego/$v" \
      && curl -fsSL "https://github.com/go-acme/lego/releases/download/$v/lego_${v}_linux_${arch}.tar.gz" | tar xz -C "/opt/lego/$v" lego; \
    done
DOCKERFILE

docker network create "$net" >/dev/null
docker run -d --name "app-$id" --network "$net" -v "$dist:/dist:ro" unitship-acme sleep infinity >/dev/null
app_ip="$(docker inspect -f "{{(index .NetworkSettings.Networks \"$net\").IPAddress}}" "app-$id")"

# Mock DNS: every name resolves to the app container (and no IPv6 address).
docker run -d --name "chall-$id" --network "$net" --network-alias chall ghcr.io/letsencrypt/pebble-challtestsrv:latest \
  -defaultIPv4 "$app_ip" -defaultIPv6 "" -doh "" -https01 "" -tlsalpn01 "" -http01 "" >/dev/null
docker run -d --name "pebble-$id" --network "$net" --network-alias pebble -e PEBBLE_VA_NOSLEEP=1 \
  ghcr.io/letsencrypt/pebble:latest -config /test/config/pebble-config.json -dnsserver chall:8053 >/dev/null
docker cp "pebble-$id:/test/certs/pebble.minica.pem" "$dist/pebble.minica.pem"
sleep 3

for v in "${versions[@]}"; do
  echo "== lego $v"
  docker exec -u unitship -e "LEGO=/opt/lego/$v" "app-$id" bash -euo pipefail -c '
    export PATH="$LEGO:$PATH" LEGO_CA_CERTIFICATES=/dist/pebble.minica.pem
    export UNITSHIP_HOME="$(mktemp -d /tmp/snh.XXXXXX)"
    bin=/dist/unitship
    trap "for f in $UNITSHIP_HOME/run/*.pid; do kill \$(cat \$f) 2>/dev/null || true; done" EXIT
    lego --version
    $bin setup --supervisor local --http-port 5002 --https-port 5443 --email a@b.c \
      --acme-server https://pebble:14000/dir --bin $bin 2>&1 | grep -v "^#\|^snunit: downloading\|%"
    $bin create web >/dev/null
    printf "#!/usr/bin/env python3\nimport os, http.server\nclass H(http.server.BaseHTTPRequestHandler):\n    def do_GET(self):\n        b = b\"hello\"\n        self.send_response(200); self.send_header(\"Content-Length\", \"5\"); self.end_headers(); self.wfile.write(b)\n    def log_message(self, *a): pass\nhttp.server.ThreadingHTTPServer((os.environ[\"BIND_ADDRESS\"], int(os.environ[\"PORT\"])), H).serve_forever()\n" > /tmp/app.py
    chmod +x /tmp/app.py
    $bin deploy web /tmp/app.py >/dev/null
    $bin domains web add web.example.com >/dev/null
    echo "-- issuing"
    $bin tls web 2>&1 | grep -E "^-----|error|Error|ERROR" || true
    body="$(curl -fsS --max-time 5 --cacert /dist/pebble.minica.pem --resolve web.example.com:5443:127.0.0.1 https://web.example.com:5443/ 2>&1 || true)"
    issuer="$(echo | openssl s_client -connect 127.0.0.1:5443 -servername web.example.com 2>/dev/null | openssl x509 -noout -issuer)"
    echo "issuer: $issuer"
    case "$issuer" in *Pebble*) ;; *) echo "not issued by Pebble"; exit 1;; esac
    # Pebble signs with an intermediate that the minica file does not contain: ignore verification of the chain.
    body="$(curl -fsSk --max-time 5 --resolve web.example.com:5443:127.0.0.1 https://web.example.com:5443/)"
    [ "$body" = hello ] || { echo "https answered: $body"; exit 1; }
    location="$(curl -s -o /dev/null -w "%{redirect_url}" -H "Host: web.example.com" http://127.0.0.1:5002/x)"
    [ "$location" = "https://web.example.com:5443/x" ] || { echo "no redirect: $location"; exit 1; }
    echo "-- renewing now (--force)"
    serial() { echo | openssl s_client -connect 127.0.0.1:5443 -servername web.example.com 2>/dev/null | openssl x509 -noout -serial; }
    before="$(serial)"
    # Without --force lego asks the CA when to renew (ARI), and Pebble answers at random.
    $bin tls renew --force 2>&1 | grep -E "^-----|error|Error|ERROR|Skip|not needed" || true
    after="$(serial)"
    echo "serial: $before -> $after (file: $(openssl x509 -noout -serial -in $UNITSHIP_HOME/certs/web.example.com.pem))"
    [ "$before" != "$after" ] || { echo "the certificate was not renewed"; exit 1; }
    body="$(curl -fsSk --max-time 5 --resolve web.example.com:5443:127.0.0.1 https://web.example.com:5443/)"
    [ "$body" = hello ] || { echo "https stopped working after renew: $body"; exit 1; }
    echo "ok"
  ' || fail "lego $v"
done
echo "ALL OK"
