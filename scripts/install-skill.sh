#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "Publishing agents4s artifacts to local Ivy cache (sbt publishLocal)..."
sbt publishLocal

echo "Removing previously installed skills..."
rm -rf ~/.agents/skills/actor-harness 2>/dev/null || true
rm -rf ~/.cursor/skills/actor-harness 2>/dev/null || true
rm -rf ~/.agents/skills/actor-spec 2>/dev/null || true
rm -rf ~/.cursor/skills/actor-spec 2>/dev/null || true

echo "Installing actor-harness skill from local source (includes actor-spec.md + actor-harness.md workflows)..."
npx --yes skills add "$REPO_ROOT" -g -a cursor --skill actor-harness -y

echo "Done! The actor-harness skill is installed. Re-run this script after editing skills/actor-harness/ (SKILL.md, actor-spec.md, actor-harness.md, references/)."
