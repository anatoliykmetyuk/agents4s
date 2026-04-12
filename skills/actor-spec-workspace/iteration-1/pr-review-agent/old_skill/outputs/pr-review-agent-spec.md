# Pull Request Diff Review Actor Specification

## Actor Purpose

This actor accepts a pull request unified diff plus GitHub coordinates and credentials, decides which changed files merit human-style code review, delegates review text generation to an LLM-backed child actor, then publishes the result on GitHub using the GitHub REST API (top-level review body and optional inline review comments on the PR’s latest diff).

## Messaging Protocol

**Receives from non-child actors**

- `StartPrReview(owner: String, repo: String, pullNumber: Int, headSha: String, unifiedDiff: String, githubAccessToken: String)` — Starts one end-to-end review for the PR identified by `owner`, `repo`, and `pullNumber`. `headSha` is the commit SHA the review must target (must match the PR head for inline comments). `unifiedDiff` is the full PR patch text (e.g. from the compare or pull API). `githubAccessToken` is a credential with permission to post pull request reviews and review comments for that repository.

**Sends back to non-child actors**

- `PrReviewSucceeded(reviewHtmlUrl: String, inlineCommentCount: Int)` — The review was created on GitHub; `reviewHtmlUrl` locates the submitted review in the web UI; `inlineCommentCount` counts inline threads created.
- `PrReviewSkipped(reason: String)` — No review was posted because nothing met the selection criteria (e.g. only lockfiles or generated assets).
- `PrReviewFailed(stage: String, detail: String)` — A hard failure occurred during validation, child processing, or GitHub API interaction; `stage` names the phase (`validate`, `select_files`, `child`, `github_api`).

## Workflow

1. Receive `StartPrReview` and validate inputs: non-blank `owner`, `repo`, positive `pullNumber`, non-blank `headSha`, non-empty `unifiedDiff`, non-empty token. On failure, reply `PrReviewFailed` with `stage` `validate` and stop.
2. Parse `unifiedDiff` into per-file change records (paths plus hunks). If parsing yields no files, reply `PrReviewSkipped` and stop.
3. Select files worth reviewing: exclude paths matching ignore rules (e.g. lockfiles, vendored trees, obvious generated output, binary extensions), enforce a maximum file count and maximum bytes of patch text per file and in total, and prefer files with substantive code changes. If the selected set is empty, reply `PrReviewSkipped` and stop.
4. Spawn the Subagent [LLM Diff Reviewer](llm-diff-reviewer-subagent-spec.md), which produces a `DiffReviewDraft` from the selected hunks.
    1. On `DiffReviewDraft`, call the GitHub REST API to create a pull request review on `pullNumber` targeting `headSha`, using `COMMENT` (or equivalent non-approve/request-changes) disposition, posting `bodyMarkdown` as the review summary and creating inline comments where `diffLineAnchor` maps to valid positions in the PR diff. Aggregate API errors into `PrReviewFailed` with `stage` `github_api` if the review cannot be published.
    2. On `DiffReviewGenerationFailed`, reply `PrReviewFailed` with `stage` `child` and stop.
5. After a successful API submission, reply `PrReviewSucceeded` with the review URL and inline comment count.
