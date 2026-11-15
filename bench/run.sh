#!/usr/bin/env bash
# Warp macro benchmark: Warp vs Velocity, Gate (full and Lite) and BungeeCord, on one command.
#
#   bench/run.sh --smoke                 # sanity run of a few minutes (one proxy, a handful of bots); try this first
#   bench/run.sh                         # full run, every proxy, every scenario
#   bench/run.sh --proxies warp,velocity # a subset
#   bench/run.sh --warp-jar path.jar     # benchmark a locally built Warp
#   bench/run.sh --help
#
# On a fresh Debian or Ubuntu box this installs nothing system-wide except what it downloads into the
# cache (~/.cache/warp-bench and ~/.cache/warp-e2e): the JDKs (Temurin, via the e2e harness), the
# proxies, and the Paper backend. Node.js 22+ must already be installed. Every download is verified
# against the sha256 pinned in bench/versions.json before use.
set -euo pipefail

bench_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$bench_dir/.." && pwd)
e2e_dir="$repo_dir/e2e"

if ! command -v node > /dev/null; then
  echo "error: Node.js 22+ is required (https://nodejs.org)" >&2
  exit 2
fi
node_major=$(node -p 'process.versions.node.split(".")[0]')
if [ "$node_major" -lt 22 ]; then
  echo "error: Node.js 22+ is required, found $(node --version)" >&2
  exit 2
fi

# The benchmark reuses the e2e harness's bot client, downloader and process helpers, so its pinned
# dependencies must be installed. The benchmark itself adds no npm dependencies of its own.
stamp="$e2e_dir/node_modules/.package-lock.json"
if [ ! -f "$stamp" ] || [ "$e2e_dir/package-lock.json" -nt "$stamp" ]; then
  echo "Installing e2e dependencies (bot client, reused by the benchmark)..."
  (cd "$e2e_dir" && npm ci --no-audit --no-fund --loglevel=error)
fi

exec node "$bench_dir/src/cli.js" "$@"
