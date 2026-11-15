#!/bin/sh
# Warp's container entry point. Runs the proxy from $WARP_HOME (/data), writing the container's
# default configuration there on first start; `console`, `health`, `drain` and `migrate` run the
# jar's tool of that name instead, with a light JVM.
set -eu

home="${WARP_HOME:-/data}"
cd "$home"

case "${1:-}" in
  console | health | drain | migrate)
    # shellcheck disable=SC2086 # JAVA_OPTS is a list of flags
    exec java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData $JAVA_OPTS \
      -jar /opt/warp/warp.jar "$@"
    ;;
esac

if [ ! -e warp.conf ]; then
  if cp /opt/warp/warp.conf warp.conf 2> /dev/null; then
    echo "Wrote the container's default warp.conf to $home/warp.conf" >&2
  else
    echo "Cannot write $home/warp.conf: mount a volume owned by uid 1000 (chown -R 1000:1000), or mount a warp.conf" >&2
    exit 78
  fi
fi

# shellcheck disable=SC2086 # both are lists of flags
exec java $WARP_JVM_OPTS $JAVA_OPTS -jar /opt/warp/warp.jar "$@"
