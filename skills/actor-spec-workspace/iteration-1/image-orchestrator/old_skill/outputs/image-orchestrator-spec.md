# Image Batch Orchestrator Actor Specification

## Actor Purpose

The Image Batch Orchestrator accepts a batch of image jobs, runs each image through resize, then watermark, then upload by delegating to dedicated worker actors, and applies a uniform retry policy of up to two retries (three attempts total) per stage when a worker reports failure, finally emitting per-item and batch-level outcomes to the caller.

## Definitions

- **ImageRef**: Opaque handle to stored image bytes (path, object key, or id) usable by workers.
- **JobId**: Correlation id unique within one batch item’s pipeline attempts (orchestrator may encode `batchId` and image index).
- **Retry budget**: At most **2 retries** after the first attempt on a given stage, i.e. **3 attempts** before that stage is considered failed for the image.

## Messaging Protocol

**Receives from non-child actors**

`ProcessImageBatch(batchId: BatchId, items: Seq[ImageItem], resizeSpec: ResizeSpec, watermarkSpec: WatermarkSpec, uploadDestination: UploadDestination)`

Starts processing. Each `ImageItem` contains `sourceRef: ImageRef` and any item-level overrides the orchestrator forwards inside child jobs as needed.

**Sends to non-child actors**

`ImagePipelineSucceeded(batchId: BatchId, itemIndex: Int, remoteUrl: Url)`

Sent when all three stages succeed for one item.

`ImagePipelineFailed(batchId: BatchId, itemIndex: Int, failedStage: StageName, reason: String, attemptsOnFailedStage: Int)`

Sent when a stage exhausts its retry budget. `StageName` is one of `Resize`, `Watermark`, `Upload`.

`BatchComplete(batchId: BatchId, succeeded: Int, failed: Int)`

Sent after every item has been settled (success or terminal failure).

## Workflow

1. Receive `ProcessImageBatch`. Initialize counters; for each index in `items`, schedule pipeline processing (concurrency policy is implementation-defined but must preserve per-item ordering of stages).
2. For each item, run the **resize** stage:
   1. Spawn the Subagent [Image Resize Worker](image-resize-worker-spec.md), which resizes one image and returns `ResizeOk` or `ResizeError`.
   2. On `ResizeOk`, store `resizedRef` for the item and proceed to watermark.
   3. On `ResizeError`, if attempts for this stage are fewer than 3, increment attempt count and repeat from 2.1; otherwise send `ImagePipelineFailed` for the item with `failedStage: Resize` and skip remaining stages for that item.
3. For the item, run the **watermark** stage using `resizedRef`:
   1. Spawn the Subagent [Image Watermark Worker](image-watermark-worker-spec.md), which watermarks one image.
   2. On `WatermarkOk`, store `watermarkedRef` and proceed to upload.
   3. On `WatermarkError`, retry up to the retry budget for this stage; on exhaustion, send `ImagePipelineFailed` with `failedStage: Watermark`.
4. For the item, run the **upload** stage using `watermarkedRef`:
   1. Spawn the Subagent [Image Upload Worker](image-upload-worker-spec.md), which uploads one file to the remote destination.
   2. On `UploadOk`, send `ImagePipelineSucceeded` with `remoteUrl`.
   3. On `UploadError`, retry up to the retry budget; on exhaustion, send `ImagePipelineFailed` with `failedStage: Upload`.
5. When all items have produced either `ImagePipelineSucceeded` or `ImagePipelineFailed`, send `BatchComplete` with total succeeded and failed counts.
