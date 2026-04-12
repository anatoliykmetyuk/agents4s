# Image Resize Worker Actor Specification

## Actor Purpose

The Image Resize Worker performs a single resize operation on one image reference supplied by its parent orchestrator and reports success with a new storage reference or failure with an error description.

## Messaging Protocol

**Receives from non-child actors**

`ResizeJob(jobId: JobId, sourceRef: ImageRef, targetSpec: ResizeSpec)`

The parent orchestrator assigns a stable `jobId` for correlation, the `sourceRef` pointing at the input bytes or object store key, and `targetSpec` describing dimensions, fit mode, and output format.

**Sends to non-child actors**

`ResizeOk(jobId: JobId, resizedRef: ImageRef)`

Emitted when the resized artifact is persisted and `resizedRef` can be passed to downstream workers.

`ResizeError(jobId: JobId, reason: String)`

Emitted when the resize cannot be completed; `reason` is suitable for logging and parent retry policy.

## Workflow

1. Receive `ResizeJob`.
2. Load bytes for `sourceRef`, decode, apply resize per `targetSpec`, encode, and write the result to storage, producing `resizedRef`.
3. Reply with `ResizeOk` on success or `ResizeError` on any failure.
