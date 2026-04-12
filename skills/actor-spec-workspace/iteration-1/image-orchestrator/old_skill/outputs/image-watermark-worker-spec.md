# Image Watermark Worker Actor Specification

## Actor Purpose

The Image Watermark Worker applies a configured watermark to one resized image and returns a new image reference for the watermarked artifact.

## Messaging Protocol

**Receives from non-child actors**

`WatermarkJob(jobId: JobId, inputRef: ImageRef, watermarkSpec: WatermarkSpec)`

The orchestrator supplies the `jobId`, the `inputRef` from the resize stage, and `watermarkSpec` (e.g. text, image asset id, opacity, placement).

**Sends to non-child actors**

`WatermarkOk(jobId: JobId, watermarkedRef: ImageRef)`

Emitted when the watermarked file is stored.

`WatermarkError(jobId: JobId, reason: String)`

Emitted when watermarking fails.

## Workflow

1. Receive `WatermarkJob`.
2. Load image at `inputRef`, composite watermark per `watermarkSpec`, write output to storage as `watermarkedRef`.
3. Reply with `WatermarkOk` or `WatermarkError`.
