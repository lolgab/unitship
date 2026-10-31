#!/usr/bin/env bash
# Builds the panel: the page (web/, Preact) is built, embedded in the sources of the panel, and `snunit package` makes the
# executable (which has FreeUnit inside, like every SNUnit app).
#
# Usage: panel/build.sh [output] [snunit package options...]      (output: dist/panel)
# Needs node and npm, and the snunit command line tool (https://github.com/lolgab/snunit) with scalino.
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-dist/panel}"
shift || true
(cd web && npm ci && npm run build && node embed.mjs dist ../panel/gen/Assets.scala)
mkdir -p "$(dirname "$out")"
snunit package panel/src panel/gen -o "$out" "$@"
