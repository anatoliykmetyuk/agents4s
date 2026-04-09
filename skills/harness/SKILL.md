---
name: harness
description: >
  Turn a markdown SOP (Standard Operating Procedure) into a Scala 3 harness
  that automates mechanical steps via script and delegates agentic steps to
  Cursor agents via cursor-driver (Scala).  Use this skill whenever the user wants to
  automate an SOP, convert a procedure into a script, harness a workflow, or
  mentions turning a markdown procedure into code that orchestrates agents.
  Also use when the user says "harness", "automate this SOP", or asks to
  create a scripted agent pipeline from a document.
---

# Harness — SOP-to-Script Automation (Scala)

You are converting a human-readable SOP (Standard Operating Procedure) written
in markdown into a self-contained **Scala 3** project that **scripts** the
mechanical parts and **delegates** the judgment-heavy parts to Cursor agents
via **`cursordriver.CursorAgent`** from the [cursor-driver-scala](.) library.

## When to use this skill

- The user has (or points you at) a markdown file describing a multi-step procedure.
- Some steps are rote and some require reasoning that an LLM agent should handle.
- The goal is a one-command `./run.sh` that executes the whole procedure.

## Step 1 — Understand the SOP

Read the SOP markdown.  For every numbered step, classify (with the user):

| Category | Examples | How it ends up |
|----------|----------|----------------|
| **Mechanical** | clone a repo, copy a file, parse JSON | Scala code in `src/main/scala` |
| **Agentic** | inspect code, summarize, judgment calls | Prompt template + `CursorAgent` |

### Identify the working directory

The **working directory** is where the harness runs and where agents open their workspace. It is **never** the harness project directory itself (where `run.sh` lives), and **never** raw `cwd` unless the SOP says so.

## Step 2 — Create the project directory

Create a **new directory** and move the SOP into it as `SOP.md`:

```
some-dir/
└── my-procedure/
    ├── SOP.md
    ├── setup.sh              # sbt compile / deps
    ├── run.sh                  # sbt runMain …
    ├── test.sh                 # sbt test (unit vs integration flags)
    ├── build.sbt               # depends on cursor-driver-scala (see boilerplate)
    ├── project/build.properties
    ├── .gitignore
    ├── src/main/scala/.../Main.scala
    ├── prompts/*.md
    ├── src/test/scala/...     # ScalaTest suites
    └── out/                    # gitignored artifacts
```

Read [references/project-boilerplate.md](references/project-boilerplate.md) for templates.

## Step 3 — Write prompt templates

Same as Python version: `{{PLACEHOLDER}}` syntax, context table, tasks, rules.

## Step 4 — Write the automation script

Read [references/script-guide.md](references/script-guide.md) for Scala patterns: `os.Path` workspace, `CursorAgent`, `sendPrompt` / `awaitDone`, chunking, concurrency (`Future`, `ExecutionContext`), CLI (`mainargs` or `scopt`).

## Step 5 — Write tests

Follow [TESTING.md](TESTING.md): ScalaTest layers, no real agents/subprocess in unit tests, constructor DI instead of monkeypatch.

## The SOP is the source of truth

Do **not** rewrite the body of `SOP.md` except an optional top note that automation runs via `./run.sh`.

## Checklist

- [ ] Working directory resolved from SOP data; `--workdir` override on the CLI.
- [ ] `SOP.md` present.
- [ ] `setup.sh` / `run.sh` executable; `build.sbt` references cursor-driver-scala.
- [ ] Every agentic step has a `prompts/` template with `{{PLACEHOLDER}}` tokens.
- [ ] Configuration (model, parallelism, tmux socket) at the top of the main entry point.
- [ ] `agent.awaitDone()` after successful agent turns that use `start` / `sendPrompt` as in the Python guide.
- [ ] Chunk long tasks across `_prompt_N.md` files; use `killSession = false` for multi-turn sessions.
- [ ] `out/` gitignored.
- [ ] `sbt test` passes (see [TESTING.md](TESTING.md)).
