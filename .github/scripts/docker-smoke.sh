#!/usr/bin/env bash
# Smoke test of the container image: starts it, waits for the proxy, asks the probes, the metrics,
# a server list ping, the remote console and the drain, then stops it with SIGTERM and checks that
# the drain let it exit cleanly.
#
# Usage: docker-smoke.sh IMAGE
#   SMOKE_REQUIRE_NATIVES=false accepts a jar without this platform's libdeflate (a CI jar built on
#   one runner has that runner's glibc library only); the release and edge jars have every one.
set -euo pipefail

image=${1:?usage: docker-smoke.sh IMAGE}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
name="warp-smoke-$$"
drain_token=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
console_token=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')

cleanup() {
  docker rm -f "$name" > /dev/null 2>&1 || true
}
trap cleanup EXIT

fail() {
  echo "::error::$*"
  echo "--- container log ---"
  docker logs "$name" 2>&1 | tail -80
  exit 1
}

step() { echo "::group::$*"; }
done_step() { echo "::endgroup::"; }

step "Start $image"
docker run -d --name "$name" \
  --read-only --tmpfs /tmp --tmpfs /data:uid=1000,gid=1000 \
  --cap-drop ALL --security-opt no-new-privileges \
  -p 127.0.0.1::25577 -p 127.0.0.1::9901 \
  -v "$here/../docker/smoke.conf:/data/warp.conf:ro" \
  -e WARP_MANAGEMENT_TOKEN="$drain_token" \
  -e WARP_CONSOLE_SMOKE_TOKEN="$console_token" \
  -e WARP_LOG_FORMAT=json \
  "$image" > /dev/null
game_port=$(docker port "$name" 25577/tcp | head -1 | sed 's/.*://')
mgmt_port=$(docker port "$name" 9901/tcp | head -1 | sed 's/.*://')
echo "game port $game_port, management port $mgmt_port"
done_step

step "Wait for /startupz (the image's health command)"
for _ in $(seq 1 60); do
  if docker exec "$name" /opt/warp/entrypoint.sh health --url http://127.0.0.1:9901/startupz > /dev/null 2>&1; then
    break
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != true ]; then
    fail "the container exited while starting"
  fi
  sleep 1
done
docker exec "$name" /opt/warp/entrypoint.sh health --url http://127.0.0.1:9901/startupz || fail "/startupz never answered 200"
done_step

step "Probes and metrics from outside"
curl -sSf "http://127.0.0.1:$mgmt_port/livez" > /dev/null || fail "/livez"
# Ready: the one backend is registered (reachable or not).
curl -sSf "http://127.0.0.1:$mgmt_port/readyz" || fail "/readyz"
# Captured first: with pipefail, `producer | grep -q` fails whenever grep exits on its first
# match before the producer wrote everything (SIGPIPE).
metrics=$(curl -sSf "http://127.0.0.1:$mgmt_port/metrics")
grep -q '^warp_players_online ' <<< "$metrics" || fail "/metrics lacks warp_players_online"
curl -sSf "http://127.0.0.1:$mgmt_port/metrics" | grep '^warp_info'
done_step

step "Server list ping"
python3 "$here/mc-status.py" 127.0.0.1 "$game_port" || fail "the status ping failed"
done_step

step "Remote console: run a command"
docker exec -e WARP_CONSOLE_TOKEN="$console_token" "$name" \
  /opt/warp/entrypoint.sh console run --url http://127.0.0.1:9901 -- warp | tee "${RUNNER_TEMP:-/tmp}/warp-smoke-console.txt"
grep -q '^Compression ' "${RUNNER_TEMP:-/tmp}/warp-smoke-console.txt" || fail "the console did not answer the /warp command"
if [ "${SMOKE_REQUIRE_NATIVES:-true}" = true ]; then
  grep -q 'Compression libdeflate' "${RUNNER_TEMP:-/tmp}/warp-smoke-console.txt" || fail "the proxy does not compress with the bundled libdeflate"
  grep -q 'from /opt/warp/cache' "${RUNNER_TEMP:-/tmp}/warp-smoke-console.txt" || fail "libdeflate was not loaded from the image's cache"
else
  echo "(native libdeflate not required of this jar)"
fi
done_step

step "Drain through the management API, then exit on SIGTERM"
docker exec "$name" /opt/warp/entrypoint.sh drain --url http://127.0.0.1:9901 || fail "POST /drain was refused"
sleep 2
if curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$mgmt_port/readyz" | grep -q '^503$'; then
  echo "/readyz says 503 while draining"
else
  fail "/readyz did not turn 503 while draining"
fi
# No player: the drain ends by itself and the proxy exits. docker stop sends SIGTERM meanwhile,
# which a draining proxy takes as a second SIGTERM only once the drain is over.
timeout 120 docker wait "$name" > "${RUNNER_TEMP:-/tmp}/warp-smoke-exit.txt" || fail "the proxy did not exit after the drain"
exit_code=$(cat "${RUNNER_TEMP:-/tmp}/warp-smoke-exit.txt")
[ "$exit_code" = 0 ] || fail "the proxy exited with $exit_code after the drain"
container_log=$(docker logs "$name" 2>&1)
grep -qi 'drain' <<< "$container_log" || fail "the log never mentions the drain"
done_step

step "SIGTERM drains: a fresh container stops cleanly"
docker rm -f "$name" > /dev/null
docker run -d --name "$name" --read-only --tmpfs /tmp --tmpfs /data:uid=1000,gid=1000 \
  -v "$here/../docker/smoke.conf:/data/warp.conf:ro" \
  -e WARP_MANAGEMENT_TOKEN="$drain_token" -e WARP_CONSOLE_SMOKE_TOKEN="$console_token" \
  "$image" > /dev/null
for _ in $(seq 1 60); do
  docker exec "$name" /opt/warp/entrypoint.sh health --url http://127.0.0.1:9901/startupz > /dev/null 2>&1 && break
  sleep 1
done
docker stop -t 90 "$name" > /dev/null
exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$name")
[ "$exit_code" = 0 ] || fail "docker stop ended with exit code $exit_code"
docker logs "$name" 2>&1 | grep -i 'sigterm\|drain' | tail -3
done_step

echo "Smoke test passed for $image"
