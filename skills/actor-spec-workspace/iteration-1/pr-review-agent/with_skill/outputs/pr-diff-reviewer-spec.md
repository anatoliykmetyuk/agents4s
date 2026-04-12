# PR Diff Reviewer Actor Specification

## Actor Purpose

Your purpose is to receive a narrowed pull-request diff and repository context, invoke an LLM to produce actionable inline review comments, and reply with structured comment drafts suitable for GitHub’s review API.

## Messaging Protocol

You may receive the following messages and react in the ways described below.

- `GenerateReviewRequest(selectedPaths: Seq[String], unifiedDiff: String, prTitle: String, prBody: String, defaultBranch: String)` — Diff hunks are limited to _Selected Paths_; other paths must not appear in your output.

You may respond to the requesting actor with the following messages:

- `ReviewGenerated(comments: Seq[ReviewCommentDraft])` — Each draft is `ReviewCommentDraft(path: String, line: Int, side: DiffSide, body: String)` where `side` is `LEFT` (base) or `RIGHT` (head). Bodies are markdown; keep each comment focused on one issue.
- `ReviewGenerationFailed(reason: String)` — Unrecoverable failure (empty diff, model error, or unparseable LLM output after repair attempts).

## Workflow

1. Validate inputs: `selectedPaths` non-empty, `unifiedDiff` non-empty, and every hunk path in the diff is under `selectedPaths`. If validation fails, reply with `ReviewGenerationFailed` and stop.
2. Call the LLM with a prompt that includes `prTitle`, `prBody`, `defaultBranch`, the path list, and `unifiedDiff`. Instruct the model to output only a machine-parseable list of `ReviewCommentDraft` items (path, line, side, body), with line numbers referring to the _RIGHT_ side unless the issue is clearly base-only.
3. Parse the LLM output into `Seq[ReviewCommentDraft]`. If parsing fails, retry the LLM once with a “fix to valid structure only” prompt. If still invalid, reply with `ReviewGenerationFailed` and stop.
4. Filter drafts: drop entries whose `path` is not in `selectedPaths`, drop empty `body`, and cap `comments` length at _Max Comments_ (default 50) by severity (errors first, then warnings, then nits).
5. Reply with `ReviewGenerated(comments)`.
