#!/usr/bin/env bash
# Manage the local Audiobookshelf v2.38.0 reference instance for Pageworn.
#
#   server-fixture/abs.sh up       start the container and wait for /status
#   server-fixture/abs.sh down     stop and remove the container
#   server-fixture/abs.sh reset    down + delete named volumes + .state
#   server-fixture/abs.sh seed     populate accounts, libraries and books
#   server-fixture/abs.sh capture  write sanitized API captures
#
# Requires docker (Colima). The image is pinned to the reference release.
#
# Named volumes, not bind mounts: this Docker runs inside a Colima VM whose
# bind mounts of host paths on external volumes can be stale (the VM does not
# see files created on the host). seed.py therefore pushes the library
# files into the running container with `docker cp`.

set -euo pipefail

IMAGE="ghcr.io/advplyr/audiobookshelf:2.38.0"
NAME="pageworn-abs"
PORT="13378"
BASE_URL="http://127.0.0.1:${PORT}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_DIR="${SCRIPT_DIR}/.state"

VOLUME_NAMES=(config metadata audiobooks second podcasts)

cmd_up() {
  if docker ps --format '{{.Names}}' | grep -qx "${NAME}"; then
    echo "container ${NAME} already running" >&2
  else
    if docker ps -a --format '{{.Names}}' | grep -qx "${NAME}"; then
      docker rm -f "${NAME}" >/dev/null
    fi
    for v in "${VOLUME_NAMES[@]}"; do
      docker volume create "pageworn-abs-${v}" >/dev/null
    done
    docker run -d --name "${NAME}" \
      -p "127.0.0.1:${PORT}:80" \
      -v "pageworn-abs-config:/config" \
      -v "pageworn-abs-metadata:/metadata" \
      -v "pageworn-abs-audiobooks:/audiobooks" \
      -v "pageworn-abs-second:/second" \
      -v "pageworn-abs-podcasts:/podcasts" \
      "${IMAGE}" >/dev/null
    echo "started ${NAME}" >&2
  fi

  echo "waiting for ${BASE_URL}/status ..." >&2
  for _ in $(seq 1 120); do
    if curl -sf -o /dev/null "${BASE_URL}/status" 2>/dev/null; then
      echo "server is up" >&2
      return 0
    fi
    sleep 1
  done
  echo "server did not answer /status within 120s" >&2
  docker logs "${NAME}" 2>&1 | tail -30 >&2 || true
  return 1
}

cmd_down() {
  if docker ps -a --format '{{.Names}}' | grep -qx "${NAME}"; then
    docker rm -f "${NAME}" >/dev/null
    echo "removed ${NAME}" >&2
  else
    echo "container ${NAME} not present" >&2
  fi
}

cmd_reset() {
  cmd_down
  for v in "${VOLUME_NAMES[@]}"; do
    docker volume rm "pageworn-abs-${v}" >/dev/null 2>&1 || true
  done
  rm -rf "${STATE_DIR}"
  echo "removed pageworn-abs-* volumes and deleted ${STATE_DIR}" >&2
}

cmd_seed() {
  python3 "${SCRIPT_DIR}/seed.py"
}

cmd_capture() {
  python3 "${SCRIPT_DIR}/capture.py"
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  reset) cmd_reset ;;
  seed) cmd_seed ;;
  capture) cmd_capture ;;
  *)
    echo "usage: $0 {up|down|reset|seed|capture}" >&2
    exit 2
    ;;
esac
