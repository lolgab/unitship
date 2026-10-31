#!/usr/bin/env bash
# Installs the unitship CLI from the latest GitHub release.
#   curl -fsSL https://lolgab.github.io/unitship/install.sh | bash
# Env: UNITSHIP_REPO (default lolgab/unitship), UNITSHIP_VERSION (default latest, e.g. v0.1.0), INSTALL_DIR (default ~/.local/bin)
set -euo pipefail
repo="${UNITSHIP_REPO:-lolgab/unitship}"
dir="${INSTALL_DIR:-$HOME/.local/bin}"
version="${UNITSHIP_VERSION:-latest}"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) asset=unitship-linux-x86_64 ;;
  Darwin-arm64) asset=unitship-macos-arm64 ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m): build from source (see README)" >&2; exit 1 ;;
esac

if [ "$version" = latest ]; then base="https://github.com/$repo/releases/latest/download"
else base="https://github.com/$repo/releases/download/$version"; fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
curl -fsSL "$base/$asset" -o "$tmp/$asset"
curl -fsSL "$base/$asset.sha256" -o "$tmp/$asset.sha256"
(cd "$tmp" && if command -v sha256sum >/dev/null; then sha256sum -c "$asset.sha256"; else shasum -a 256 -c "$asset.sha256"; fi)

mkdir -p "$dir"
install -m 755 "$tmp/$asset" "$dir/unitship"
echo "installed $dir/unitship"
case ":$PATH:" in *":$dir:"*) ;; *) echo "add $dir to your PATH" ;; esac
