# Image Upload Worker Actor Specification

## Actor Purpose

The Image Upload Worker uploads one finalized image artifact to a remote destination (e.g. CDN or object bucket) and returns a durable public or signed URL.

## Messaging Protocol

**Receives from non-child actors**

`UploadJob(jobId: JobId, fileRef: ImageRef, destination: UploadDestination)`

The orchestrator provides `jobId`, the `fileRef` to upload (typically post-watermark), and `destination` describing bucket, key prefix, ACL, and optional cache headers.

**Sends to non-child actors**

`UploadOk(jobId: JobId, remoteUrl: Url)`

Emitted when the object is committed and `remoteUrl` resolves the asset.

`UploadError(jobId: JobId, reason: String)`

Emitted when the upload fails or the remote confirms an error.

## Workflow

1. Receive `UploadJob`.
2. Stream or copy bytes from `fileRef` to `destination`, verify success response from the remote API or storage SDK.
3. Reply with `UploadOk` including `remoteUrl`, or `UploadError`.
