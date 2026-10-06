#!/usr/bin/env bash
# Warp end-to-end tests: real-protocol bots → Warp → real Minecraft servers.
#
#   e2e/run.sh --mc 1.21.4                      # default variant (online mode)
#   e2e/run.sh --mc 1.8.8,1.20.2 --variants online,offline,transcode
#   e2e/run.sh --mc 1.21.4 --passthrough off --scenarios login,switching
#   e2e/run.sh --list                           # every version in the matrix
#   e2e/run.sh --help
#
# Needs Node.js 22+ and a JDK able to run Gradle; server JDKs, Paper and ViaProxy are downloaded
# into ~/.cache/warp-e2e on first use (override with WARP_E2E_CACHE).
set -euo pipefail

e2e_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if ! command -v node > /dev/null; then
  echo "error: Node.js 22+ is required (https://nodejs.org)" >&2
  exit 2
fi
node_major=$(node -p 'process.versions.node.split(".")[0]')
if [ "$node_major" -lt 22 ]; then
  echo "error: Node.js 22+ is required, found $(node --version)" >&2
  exit 2
fi

# Install the pinned dependencies when missing or when the lockfile changed.
stamp="$e2e_dir/node_modules/.package-lock.json"
if [ ! -f "$stamp" ] || [ "$e2e_dir/package-lock.json" -nt "$stamp" ]; then
  (cd "$e2e_dir" && npm ci --no-audit --no-fund --loglevel=error)
fi

exec node "$e2e_dir/src/cli.js" "$@"
