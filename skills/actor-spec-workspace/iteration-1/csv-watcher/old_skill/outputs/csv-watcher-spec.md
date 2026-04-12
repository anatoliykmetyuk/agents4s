# CSV Watcher Actor Specification

## Actor Purpose

The CSV Watcher actor monitors a single watch directory for newly arrived CSV files, checks each file’s header row against a configured ordered list of expected column names, and routes the file to either an output directory (when valid) or a quarantine directory (when invalid). For invalid files it also writes a human-readable rejection report alongside the quarantined file so operators can correct or discard the data without blocking the pipeline.

## Messaging Protocol

### Messages the actor receives from non-child actors

- `ConfigureAndStart(watchDir: Path, outputDir: Path, quarantineDir: Path, expectedColumns: Seq[String], headerRowIndex: Int)`  
  Supplies absolute or workspace-rooted paths for the watch, accepted-output, and quarantine locations, the ordered list of column names the header row must match (the header row is the row at `headerRowIndex`, zero-based), and replaces any prior watch configuration. After validating that all three directories exist or can be created per deployment policy, the actor begins (or restarts) filesystem watching on `watchDir`.

- `StopWatching()`  
  Cancels directory registration, drains or ignores in-flight file events according to a defined shutdown policy, and stops processing until another `ConfigureAndStart` arrives.

### Messages the actor sends to non-child actors

- `WatchingStarted(watchDir: Path)`  
  Confirms that configuration was applied and the watch is active.

- `FileAccepted(sourcePath: Path, destinationPath: Path)`  
  Reports that a CSV file passed schema validation and was atomically moved into `outputDir` under a unique final name (e.g. preserved basename with collision suffix if needed).

- `FileQuarantined(sourcePath: Path, quarantinedFilePath: Path, reportPath: Path, reasons: Seq[String])`  
  Reports that a CSV file failed validation; the original bytes were moved to `quarantineDir`, and `reportPath` points to a rejection report listing each validation failure.

- `WatchFailure(operation: String, path: Path, detail: String)`  
  Reports an I/O error, permission problem, or other unexpected condition while watching, reading, moving, or writing reports; does not imply the watch has stopped unless followed by explicit teardown logic in the workflow.

## Definitions

- **Stable file**: A regular file in `watchDir` that is not a hidden or temporary artifact (implementation may ignore names matching `*.tmp`, `.*`, etc.), has non-zero size, and has not been modified for a configured quiet period (e.g. last modification time unchanged for two consecutive checks), so writers are assumed finished.
- **Header validation**: The row at `headerRowIndex` is split as CSV fields (RFC 4180-style quoting), trimmed of surrounding whitespace, and compared to `expectedColumns` in order: same length and each position equal under exact string match unless a case-insensitivity flag is documented in deployment notes (default: case-sensitive).

## Workflow

1. Upon `ConfigureAndStart`, validate parameters: non-empty `expectedColumns`, distinct `watchDir`, `outputDir`, and `quarantineDir`, and ensure `watchDir` is readable and both destination roots are writable. If validation fails, reply with `WatchFailure` and do not start a watch.
2. Register a recursive or non-recursive directory watch on `watchDir` (per product default: non-recursive, files only in the top level). Send `WatchingStarted(watchDir)`.
3. When a filesystem event indicates a new or closed-write file candidate in `watchDir`, schedule a stability check; when the file is **stable**, treat it as a processing job.
   1. Open the file read-only and read enough lines to reach `headerRowIndex`; parse the header row into fields.
   2. Compare the parsed header to `expectedColumns` per **Header validation**.
   3. If valid: atomically move the file into `outputDir` (handle name collisions). Send `FileAccepted` with final paths.
   4. If invalid: build `reasons` (e.g. wrong column count, mismatch at index *i*, unexpected name). Write a UTF-8 rejection report file in `quarantineDir` containing timestamp, original filename, expected columns, actual header fields, and the reason list. Atomically move the CSV into `quarantineDir` (distinct name from report). Send `FileQuarantined` with paths and `reasons`.
   5. On any I/O or parse error during steps 3.1–3.4, send `WatchFailure` with the failing operation and path; leave the file unmoved if its state cannot be determined safely, or move to quarantine with reason “processing error” if the product policy requires clearing the watch directory.
4. Upon `StopWatching`, cancel watches, complete or abort pending stability checks per policy, and acknowledge completion to the caller if the messaging protocol includes an ack (optional companion message not listed above may be added by implementers).
