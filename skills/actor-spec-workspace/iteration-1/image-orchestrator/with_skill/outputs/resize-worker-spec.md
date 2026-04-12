# Resize Worker Actor Specification

## Actor Purpose

Your purpose is to resize a single source image to configured dimensions and write a new raster file to a deterministic working location so downstream actors can consume it.

## Messaging Protocol

You may receive the following messages from non-child actors and react as described.

- `ResizeImage(jobId: String, sourceUri: URI, targetWidthPx: Int, targetHeightPx: Int, outputPath: Path)` — carry out a single resize for the identified job; write the result to `outputPath`.

You may respond to the requesting actor with the following messages:

- `ResizeSucceeded(jobId: String, outputPath: Path)` — the resized file is ready at `outputPath`.
- `ResizeFailed(jobId: String, reason: String)` — resize could not be completed; `reason` is suitable for logs and parent retry decisions.

## Workflow

1. Receive `ResizeImage`.
2. Load the image from `sourceUri`, resize it to `targetWidthPx` by `targetHeightPx` using the image library configured for the harness, and encode the result to the same format family as the source when practical.
3. If loading, decoding, resizing, or writing fails, reply with `ResizeFailed` and stop.
4. Otherwise reply with `ResizeSucceeded` and stop.
