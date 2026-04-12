# Image Orchestrator Actor Specification

## Actor Purpose

Your purpose is to accept a batch of image jobs, run each through resize, then watermark, then upload by delegating to dedicated worker actors, and to report a per-image outcome to the requester. When any worker fails for an image, you retry that image’s pipeline from the resize step, up to two retries per image (three pipeline attempts total).

## Messaging Protocol

You may receive the following messages from non-child actors and react as described.

- `ProcessImageBatch(batchId: String, items: List[ImageItem], resizeConfig: ResizeConfig, watermarkConfig: WatermarkConfig, uploadConfig: UploadConfig)` — process every `ImageItem` in order. Each `ImageItem` carries `imageId`, `sourceUri`, and `destinationKey`. `ResizeConfig` supplies `targetWidthPx` and `targetHeightPx` plus a _Working Root_ base path for intermediate files. `WatermarkConfig` supplies `watermarkAssetPath`, `opacity`, and `position`. `UploadConfig` supplies `contentType` rules or a default `contentType` string.

You may respond to the requesting actor with the following messages:

- `ImageBatchCompleted(batchId: String, results: List[ImagePipelineResult])` — all items have finished. Each `ImagePipelineResult` is either `Succeeded(imageId, remoteUri: URI)` or `Failed(imageId, lastError: String, attemptsUsed: Int)`.

## Workflow

1. Receive `ProcessImageBatch` and initialize an empty _Results_ list for `batchId`.
2. For each `ImageItem` in `items` in list order:
    1. Set _Pipeline Attempts_ to `0`.
    2. Compute deterministic paths under _Working Root_ for this `imageId` (resized path, watermarked path) per harness conventions.
    3. Spawn the Subagent [Resize Worker](resize-worker-spec.md), which produces a resized file or reports failure. If it replies with success, continue to the next substep. If it replies with failure, go to substep 2.7.
    4. Spawn the Subagent [Watermark Worker](watermark-worker-spec.md), which watermarks the resized file or reports failure. If it replies with success, continue to the next substep. If it replies with failure, go to substep 2.7.
    5. Spawn the Subagent [Upload Worker](upload-worker-spec.md), which uploads the watermarked file or reports failure. If it replies with success, append `Succeeded` with the returned `remoteUri` to _Results_ and continue the outer loop at the next `ImageItem`. If it replies with failure, go to substep 2.7.
    6. After a full success for this item, delete or leave intermediate files according to harness cleanup policy (optional); then continue the outer loop at the next `ImageItem`.
    7. Increment _Pipeline Attempts_ by `1`. If _Pipeline Attempts_ is less than or equal to `2`, go back to substep 2.3 to retry the full pipeline (resize → watermark → upload) for the same `ImageItem` from a clean working state. If _Pipeline Attempts_ is greater than `2`, append `Failed` with the most recent worker error and `attemptsUsed` equal to `3`, then continue the outer loop at the next `ImageItem`.
3. When every `ImageItem` has a terminal result, send `ImageBatchCompleted` with _Results_ and end.
