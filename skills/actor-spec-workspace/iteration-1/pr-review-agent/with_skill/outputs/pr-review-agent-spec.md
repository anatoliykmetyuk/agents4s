# PR Review Agent Actor Specification

## Actor Purpose

Your purpose is to accept a pull request’s unified diff plus GitHub coordinates, decide which changed files merit review, delegate LLM-authored review text to a child actor, and publish the result as GitHub pull request review comments using the REST API.

## Messaging Protocol

You may receive the following messages and react in the ways described below.

- `ReviewPullRequestRequest(owner: String, repo: String, pullNumber: Int, headSha: String, baseSha: String, defaultBranch: String, unifiedDiff: String, prTitle: String, prBody: String, githubToken: String)` — `githubToken` is a bearer token with permission to post PR review comments on _Repository_ `owner/repo`. Treat it as secret: do not log or forward it to the subagent.

You may respond to the requesting actor with the following messages:

- `PrReviewComplete(postedCount: Int, skippedPaths: Seq[String])` — Review comments were created on the PR; `skippedPaths` lists files you analyzed but excluded from subagent review.
- `PrReviewFailed(reason: String)` — No review was posted (auth error, API failure after retries, subagent failure, or empty actionable set).

## Workflow

1. Receive `ReviewPullRequestRequest`. If `unifiedDiff` is empty, reply `PrReviewFailed` and stop.
2. Analyze `unifiedDiff` to build _Candidate Paths_ (every file path appearing in the diff). Classify paths: skip _Generated Or Binary Patterns_ (e.g. `*.lock`, `dist/`, `node_modules/`, `*.min.js`, `*.png`); cap remaining files at _Max Review Files_ (default 25) by preferring source extensions (`.scala`, `.java`, `.ts`, `.py`, etc.) and larger changed-line counts.
3. If _Candidate Paths_ is empty after filtering, reply `PrReviewComplete(postedCount = 0, skippedPaths = all parsed paths)` and stop.
4. Build `narrowDiff`: the subset of `unifiedDiff` containing only hunks for _Candidate Paths_.
5. Spawn the Subagent [PR Diff Reviewer](pr-diff-reviewer-spec.md) with `GenerateReviewRequest(selectedPaths = _Candidate Paths_, unifiedDiff = narrowDiff, prTitle, prBody, defaultBranch)` from the request. It returns structured inline comments or a failure.
    1. On `ReviewGenerationFailed`, reply `PrReviewFailed` including the reason and stop.
    2. On `ReviewGenerated` with empty `comments`, reply `PrReviewComplete(postedCount = 0, skippedPaths = paths excluded in step 2)` and stop.
    3. On `ReviewGenerated` with non-empty `comments`, continue.
6. For each comment in order, create a single review comment via GitHub’s API: `POST /repos/{owner}/{repo}/pulls/{pullNumber}/comments` with `body`, `commit_id` = `headSha`, `path`, `line` (and `side` if required by the API for multi-line context). On HTTP 4xx/5xx, wait with exponential backoff and retry up to 3 attempts per comment; if any comment still fails after 3 attempts, reply `PrReviewFailed` with the last error summary and stop.
7. Reply `PrReviewComplete` with `postedCount` equal to the number of successfully created comments and `skippedPaths` equal to paths from the full diff that were filtered out in step 2.
