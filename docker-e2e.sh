#!/usr/bin/env bash
# Runs e2e.sh in a Debian container with systemd, as an unprivileged user on ports 80 and 443: the way a real server
# is set up. Needs docker and a unitship built for Linux of the architecture of the container, for example:
#
#   scalino package vendor src -o dist/unitship --native-target-triple aarch64-unknown-linux-musl
#   ./docker-e2e.sh dist/unitship
set -euo pipefail

BIN="$(cd "$(dirname "${1:?usage: docker-e2e.sh <unitship binary for linux>}")" && pwd)/$(basename "$1")"
HERE="$(cd "$(dirname "$0")" && pwd)"
dist="$(mktemp -d "${TMPDIR:-/tmp}/snh-docker.XXXXXX")"
name="unitship-e2e-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true; rm -rf "$dist"' EXIT

cp "$BIN" "$dist/unitship"
cp "$HERE/e2e.sh" "$dist/e2e.sh"
chmod 755 "$dist/unitship" "$dist/e2e.sh"

docker build -q -t unitship-e2e - <<'DOCKERFILE' >/dev/null
FROM debian:stable-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends systemd systemd-sysv dbus dbus-user-session curl ca-certificates \
      python3 openssl tar git procps \
 && rm -rf /var/lib/apt/lists/* \
 && useradd -m -s /bin/bash unitship
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
DOCKERFILE

docker run -d --name "$name" --privileged --cgroupns=host --tmpfs /run --tmpfs /run/lock \
  -v /sys/fs/cgroup:/sys/fs/cgroup:rw -v "$dist:/dist:ro" unitship-e2e >/dev/null

# What `unitship setup` asks root for.
for _ in $(seq 1 30); do
  docker exec "$name" systemctl is-system-running 2>/dev/null | grep -qE 'running|degraded' && break
  sleep 1
done
docker exec "$name" sysctl -q -w net.ipv4.ip_unprivileged_port_start=80
docker exec "$name" loginctl enable-linger unitship
uid="$(docker exec "$name" id -u unitship)"
for _ in $(seq 1 30); do
  docker exec "$name" test -S "/run/user/$uid/bus" && break
  sleep 1
done

docker exec -u unitship -e "XDG_RUNTIME_DIR=/run/user/$uid" -e SUPERVISOR=systemd -e HTTP=80 -e HTTPS=443 \
  "$name" /dist/e2e.sh /dist/unitship
