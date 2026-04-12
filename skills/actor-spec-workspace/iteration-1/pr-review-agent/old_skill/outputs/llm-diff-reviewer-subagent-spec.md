# LLM Diff Reviewer Subagent Actor Specification

## Actor Purpose

This actor turns a bounded, pre-filtered pull request diff into a structured code review: a markdown summary suitable for a top-level PR comment plus optional inline comments anchored to specific lines in changed files. All judgment and wording are produced via an LLM; the actor validates that outputs are well-formed before replying to its parent.

## Messaging Protocol

**Receives from non-child actors**

- `GenerateDiffReview(bundle: ReviewableDiffBundle)` — Carries repository coordinates (`owner`, `repo`, `pullNumber`, `headSha`), and a list of `SelectedFileDiff` entries. Each entry contains `path` and the unified-diff hunks selected for that path (already truncated to implementation-defined size limits by the parent).

**Sends back to non-child actors**

- `DiffReviewDraft(bodyMarkdown: String, inlineComments: Seq[InlineReviewComment])` — `bodyMarkdown` is the overall review narrative. Each `InlineReviewComment` contains `path`, `diffLineAnchor` (line number or side-specific position the parent can map to GitHub review comment placement), and `body` (short, actionable comment).
- `DiffReviewGenerationFailed(reason: String)` — The LLM call, parsing, or local validation failed; `reason` is safe to log (no secrets).

## Workflow

1. Receive `GenerateDiffReview` and verify `bundle.selectedFiles` is non-empty and every `path` is non-blank.
2. Build an LLM task prompt from the bundle: instruct the model to focus on correctness, security, API/behavior changes, regressions, tests, and maintainability; forbid inventing issues not supported by the supplied hunks; require structured output matching `DiffReviewDraft` fields.
3. Invoke the LLM backend and obtain a structured response (implementation maps this to `bodyMarkdown` and `inlineComments`).
4. Validate the draft: `bodyMarkdown` non-empty after trim; each inline comment has non-empty `path` and `body`; drop or repair comments that reference paths absent from `bundle.selectedFiles` or clearly outside provided hunks; if nothing usable remains for the narrative, reply with `DiffReviewGenerationFailed` and stop.
5. Reply to the caller with `DiffReviewDraft` carrying the validated content.
