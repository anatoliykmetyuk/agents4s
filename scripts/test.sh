#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${1:-}" == -i || "${1:-}" == --integration ]]; then
  export CURSOR_DRIVER_INTEGRATION=1
  shift
  exec sbt test "$@"
else
  exec sbt "testOnly cursordriver.TuiOpsTest cursordriver.CursorAgentUnitTest" "$@"
fi
