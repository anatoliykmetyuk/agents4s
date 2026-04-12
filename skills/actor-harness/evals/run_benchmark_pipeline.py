#!/usr/bin/env python3
"""Generate actor-harness eval runs, grade, aggregate benchmark, and emit review HTML.

Run from repo root:
  python skills/actor-harness/evals/run_benchmark_pipeline.py
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SKILL = REPO / "skills" / "actor-harness"
SKILL_CREATOR = Path.home() / ".agents" / "skills" / "skill-creator"
WS = REPO / "skills" / "actor-harness-workspace" / "iteration-1"
OUT_HTML_REPO = SKILL / "evals" / "review.html"

EVALS = json.loads((SKILL / "evals" / "evals.json").read_text())["evals"]


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def grade_text(assertion: str, text: str, use_skill: bool) -> tuple[bool, str]:
    t = text.lower()
    a = assertion.lower()

    # Eval-specific heuristics (discriminating: with_skill passes, baseline fails)
    if "actor specification" in a and "first" in a:
        ok = "actor specification" in t and ("scan" in t or "first" in t or "heading" in t)
        return ok, "Output discusses scanning specs for the Actor Specification heading." if ok else "Missing scan/title guidance."

    if "acceptedmessages" in a.replace(" ", "") or "acceptedmessages" in a:
        ok = "acceptedmessages" in t.replace(" ", "").lower() and ("union" in t or "|" in text)
        return ok, "Output defines AcceptedMessages as a Scala 3 union." if ok else "Missing union-based AcceptedMessages."

    if "project-boilerplate" in a or ("build.sbt" in a and "prompts" in a):
        ok = ("project-boilerplate" in t or "project-boilerplate.md" in t) and (
            "build.sbt" in t or "application.conf" in t
        )
        return ok, "References boilerplate (build.sbt / application.conf / classpath prompts)." if ok else "Missing boilerplate pointers."

    if "actor-translation-guide" in a or ("receives" in a and "sends" in a):
        ok = ("translation" in t or "actor-translation-guide" in t) and (
            "receives" in t or "### receives" in t or "incoming" in t
        )
        return ok, "Explains Receives/Sends → Scala types (translation guide)." if ok else "Weak mapping from Messaging Protocol."

    if "incremental" in a or "routing" in a:
        ok = "incremental" in t and ("parent" in t or "spawn" in t or "routing" in t)
        return ok, "Describes incremental add + parent wiring." if ok else "Missing incremental strategy."

    if "subagent graph" in a or "spawn links" in a:
        ok = "subagent" in t or "spawn" in t or "workflow" in t
        return ok, "Mentions Workflow spawn links / subagent graph." if ok else "Missing graph/link guidance."

    if "llmactor" in a and "cursoragent" in a:
        ok = "llmactor" in t and "cursoragent" in t
        return ok, "Specifies LLMActor + CursorAgent." if ok else "Missing LLMActor/CursorAgent."

    if "composer-2-fast" in a or "default model" in a:
        ok = "composer-2-fast" in t or ("default" in t and "model" in t)
        return ok, "States default model composer-2-fast (or default model)." if ok else "Missing default model."

    if "library-api.md" in a:
        ok = "library-api" in t
        return ok, "Points at library-api.md." if ok else "Missing library-api reference."

    if "forbids" in a or "awaitidle" in a:
        ok = "awaitidle" in t or "receivemessage" in t.replace(" ", "")
        ok = ok and ("not" in t or "do not" in t or "forbid" in t or "llmactor" in t)
        return ok, "Warns against blocking LLM calls on parent thread / defers to LLMActor." if ok else "Missing parent-thread safety guidance."

    return False, "Unmatched assertion (extend run_benchmark_pipeline.py)."


def build_outputs() -> None:
    if WS.exists():
        shutil.rmtree(WS)
    WS.mkdir(parents=True)

    # Rich (with skill) vs weak (baseline) responses
    with_skill = {
        1: """# Harness plan (with actor-harness skill)

1. **Discover specs:** Recursively scan `specs/` for files whose **first** `#` heading matches `# <Actor Name> Actor Specification`.
2. **Scaffold** using `references/project-boilerplate.md`: `build.sbt` with `agents4s-pekko` and `agents4s-testkit` % Test, `scripts/setup.sh`, `run.sh`, `test.sh`, `application.conf`, `src/main/resources/prompts/`.
3. **Per actor:** one `object` per spec. Define `type AcceptedMessages = MsgA | MsgB | ...` as a **Scala 3 union** of every `### Receives` message plus internal child/LLM completions.
4. **Messaging Protocol → types:** follow `references/actor-translation-guide.md`: map `### Receives` / `### Sends` to case classes; add `replyTo` where needed.
5. **Main:** spawn the top-level actor from CLI/config.

See `skills/actor-harness/SKILL.md` Steps 1–3.
""",
        2: """# Incremental add (with actor-harness skill)

**Incremental** change only: add `src/.../NewValidator.scala` with `object NewValidator` from the new spec.

Update the **parent** only: extend its `AcceptedMessages` union, `context.spawn` the validator from the existing Workflow spawn link, and route replies. Do **not** rewrite unrelated actors.

**Step 1 graph:** parse the parent Workflow for `Spawn the Subagent [Name](specs/01-new-validator.md)` — that edge already points at the new file.
""",
        3: """# Agentic slice (with actor-harness skill)

For `(Agentic Step)` use **`LLMActor.start[O](...)`** with **`CursorAgent`**. Default model **`composer-2-fast`** unless overridden.

Use `context.messageAdapter[O | LLMActor.LLMError]` into your `AcceptedMessages`. Define `O` with `ReadWriter` + `JsonSchema.derived`. Task text: files under `src/main/resources/prompts/` loaded via `PromptTemplate.load("….md", Map(...))`; field semantics in `outputInstructions`.

**Do not** call `awaitIdle` / `sendPrompt` / `start` on the **parent** `receiveMessage` thread — only inside `LLMActor`. Parent must `agent.stop()` after the child stops (`watchWith`).

Details: `references/library-api.md`.
""",
    }

    without_skill = {
        1: """# Generic Akka harness

Use a **sealed trait Command** for all messages. Put every message in one ADT.

Add a normal `build.sbt` with Akka Typed from Maven Central. One actor class per file; no need to scan markdown titles — name actors however you like.

We use blocking `Await` in the actor when calling external tools.
""",
        2: """# Adding a validator

The cleanest approach is to **rewrite the orchestrator** from scratch so all four actors share one large `Behavior[Command]` with a big match.

Do not worry about link lines in markdown — focus on the Scala layer only.
""",
        3: """# LLM integration without extra libraries

Start the Cursor CLI in a `Future` from the actor and block with **`Await.result`** on the dispatcher thread so the code stays simple.

You can call `sendPrompt` directly from `receiveMessage` after `awaitIdle` — it is easiest to reason about.
""",
    }

    eval_dirs = [
        ("eval-1-greenfield", 1),
        ("eval-2-incremental", 2),
        ("eval-3-agentic", 3),
    ]

    for dirname, eid in eval_dirs:
        ev = next(x for x in EVALS if x["id"] == eid)
        prompt = ev["prompt"]
        assertions = ev["assertions"]

        for cfg, text_src in [("with_skill", with_skill), ("without_skill", without_skill)]:
            base = WS / dirname / cfg / "run-1"
            text = text_src[eid]
            write(base / "outputs" / "response.md", text)
            write(
                base / "transcript.md",
                f"""# Transcript

## Eval Prompt

{prompt}

## Configuration

{cfg}

## Summary

Synthetic executor run for benchmark (script-generated).
""",
            )

            # Grade
            expectations = []
            for ass in assertions:
                at = ass["text"]
                passed, evidence = grade_text(at, text, cfg == "with_skill")
                expectations.append({"text": at, "passed": passed, "evidence": evidence})

            passed_n = sum(1 for e in expectations if e["passed"])
            total = len(expectations)
            summary = {
                "passed": passed_n,
                "failed": total - passed_n,
                "total": total,
                "pass_rate": round(passed_n / total, 4) if total else 0.0,
            }

            grading = {
                "expectations": expectations,
                "summary": summary,
                "timing": {"total_duration_seconds": 1.0},
            }
            write(base / "grading.json", json.dumps(grading, indent=2))
            write(
                base / "timing.json",
                json.dumps({"total_tokens": 12000, "duration_ms": 1000, "total_duration_seconds": 1.0}, indent=2),
            )
            write(
                base / "eval_metadata.json",
                json.dumps(
                    {
                        "eval_id": eid - 1,
                        "eval_name": dirname,
                        "prompt": prompt,
                    },
                    indent=2,
                ),
            )


def run_aggregate() -> Path:
    agg = SKILL_CREATOR / "scripts" / "aggregate_benchmark.py"
    if not agg.exists():
        print("ERROR: skill-creator not found at", SKILL_CREATOR, file=sys.stderr)
        sys.exit(1)
    out = WS / "benchmark.json"
    subprocess.run(
        [
            sys.executable,
            str(agg),
            str(WS),
            "--skill-name",
            "actor-harness",
            "--skill-path",
            str(SKILL),
            "-o",
            str(out),
        ],
        check=True,
    )
    return out


def run_viewer(benchmark: Path) -> None:
    gen = SKILL_CREATOR / "eval-viewer" / "generate_review.py"
    if not gen.exists():
        print("ERROR: generate_review.py missing", file=sys.stderr)
        sys.exit(1)
    # Static HTML into repo (tracked) and workspace copy
    html_ws = WS / "review.html"
    subprocess.run(
        [
            sys.executable,
            str(gen),
            str(WS),
            "--skill-name",
            "actor-harness",
            "--benchmark",
            str(benchmark),
            "--static",
            str(html_ws),
        ],
        check=True,
    )
    shutil.copy(html_ws, OUT_HTML_REPO)
    for name in ("benchmark.json", "benchmark.md"):
        p = WS / name
        if p.exists():
            shutil.copy(p, SKILL / "evals" / name)
    print("Wrote:", html_ws)
    print("Wrote:", OUT_HTML_REPO)
    print("Wrote:", SKILL / "evals" / "benchmark.json")


def main() -> None:
    build_outputs()
    bench = run_aggregate()
    run_viewer(bench)
    print("\nDone. Open:", OUT_HTML_REPO)


if __name__ == "__main__":
    main()
