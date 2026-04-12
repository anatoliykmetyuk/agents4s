# Upload Worker Actor Specification

## Actor Purpose

Your purpose is to upload a single local image file to a configured remote object store and return a stable reference (URI) for the uploaded object.

## Messaging Protocol

You may receive the following messages from non-child actors and react as described.

- `UploadImage(jobId: String, localPath: Path, destinationKey: String, contentType: String)` — upload the bytes at `localPath` under `destinationKey` with the given `contentType`.

You may respond to the requesting actor with the following messages:

- `UploadSucceeded(jobId: String, remoteUri: URI)` — upload finished; `remoteUri` identifies the object.
- `UploadFailed(jobId: String, reason: String)` — upload could not be completed.

## Workflow

1. Receive `UploadImage`.
2. Stream or read `localPath`, put the object to the store using `destinationKey` and `contentType`, and obtain `remoteUri` from the client response.
3. If the store returns an error or the file is missing or unreadable, reply with `UploadFailed` and stop.
4. Otherwise reply with `UploadSucceeded` and stop.
