#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "Publishing agents4s artifacts to local Ivy cache (sbt publishLocal)..."
sbt publishLocal

echo "Removing previously installed skills..."
rm -rf ~/.agents/skills/harness 2>/dev/null || true
rm -rf ~/.cursor/skills/harness 2>/dev/null || true
rm -rf ~/.agents/skills/actor-spec 2>/dev/null || true
rm -rf ~/.cursor/skills/actor-spec 2>/dev/null || true

echo "Installing harness skill from local source..."
npx skills add "$REPO_ROOT" -g -a cursor --skill harness -y

echo "Installing actor-spec skill from local source..."
npx skills add "$REPO_ROOT" -g -a cursor --skill actor-spec -y

echo "Done! The harness and actor-spec skills are installed. Re-run this script after editing skills/harness/ or skills/actor-spec/."
