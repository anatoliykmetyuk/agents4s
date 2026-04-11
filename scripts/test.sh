#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${1:-}" == -i || "${1:-}" == --integration ]]; then
  export CURSOR_DRIVER_INTEGRATION=1
  shift
  exec sbt test "$@"
else
  exec sbt "testOnly agents4s.cursor.CursorTuiOpsTest agents4s.cursor.CursorAgentUnitTest agents4s.tmux.PathsTest agents4s.tmux.PaneTraitTest agents4s.pekko.LLMActorSpec" "$@"
fi
