# Watermark Worker Actor Specification

## Actor Purpose

Your purpose is to apply a configured watermark to a single on-disk image and write the watermarked output to a path supplied by the caller.

## Messaging Protocol

You may receive the following messages from non-child actors and react as described.

- `WatermarkImage(jobId: String, inputPath: Path, watermarkAssetPath: Path, outputPath: Path, opacity: Double, position: WatermarkPosition)` — apply the watermark from `watermarkAssetPath` onto the image at `inputPath` and write to `outputPath`. `WatermarkPosition` is one of `TopLeft`, `TopRight`, `BottomLeft`, `BottomRight`, `Center`.

You may respond to the requesting actor with the following messages:

- `WatermarkSucceeded(jobId: String, outputPath: Path)` — the watermarked file is ready at `outputPath`.
- `WatermarkFailed(jobId: String, reason: String)` — watermarking could not be completed.

## Workflow

1. Receive `WatermarkImage`.
2. Load `inputPath` and `watermarkAssetPath`, composite the watermark at `position` with `opacity`, and encode to `outputPath`.
3. If any step fails, reply with `WatermarkFailed` and stop.
4. Otherwise reply with `WatermarkSucceeded` and stop.
