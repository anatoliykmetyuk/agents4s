#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "${1:-}" == -u || "${1:-}" == --unit-only ]]; then
  shift
  exec sbt clean coverage "testOnly agents4s.cursor.CursorTuiOpsTest agents4s.cursor.CursorAgentSpec agents4s.tmux.PathsTest agents4s.tmux.PaneTraitTest agents4s.pekko.LLMActorSpec" coverageReport "$@"
elif [[ "${1:-}" == -i || "${1:-}" == --integration ]]; then
  echo "Note: -i/--integration is no longer needed; full suite runs by default." >&2
  shift
  exec sbt clean coverage test coverageReport "$@"
else
  exec sbt clean coverage test coverageReport "$@"
fi
