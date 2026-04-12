# CsvDirectoryWatcher Actor Specification

## Actor Purpose

Your purpose is to monitor a _Watch Directory_ for new CSV files, validate each file’s header row against a configured list of _Expected Column Names_, and relocate every finished file: valid files go to the _Output Directory_, and invalid files go to the _Quarantine Directory_ together with a human-readable _Rejection Report_. You run until told to stop; you do not delegate work to child actors.

## Definitions

- _Watch Directory_: absolute path you scan for incoming `.csv` files (non-recursive unless configured otherwise; default is non-recursive).
- _Output Directory_: absolute path where schema-valid CSV files are moved after successful validation.
- _Quarantine Directory_: absolute path where invalid CSV files are moved, each alongside its report.
- _Expected Column Names_: ordered list of column header strings the first data row of the CSV must match exactly (after trimming ASCII whitespace), same length and same names in the same order as the first row of the file.
- _Rejection Report_: a UTF-8 text file describing why validation failed (for example missing column, extra column, wrong order, unreadable header, empty file, or I/O error).

## Messaging Protocol

You may receive the following messages from non-child actors:

- `StartCsvWatch(config: CsvWatchConfig)` — carries _Watch Directory_, _Output Directory_, _Quarantine Directory_, _Expected Column Names_, and optional settings such as `recursive: Boolean` (default false) and `pollIntervalMs: Long` (default 1000). Begins or restarts watching according to the new configuration. If a watch is already active, apply the new configuration and continue without dropping in-flight per-file work.

- `StopCsvWatch` — stop scheduling new work; finish any file currently being validated and moved, then go idle.

You may send the following messages back to the requesting or interested actor (as your deployment wires replies):

- `CsvWatchStarted(configSnapshot: CsvWatchConfig)` — you have accepted `StartCsvWatch` and begun monitoring.

- `CsvWatchStopped` — you have fully shut down after `StopCsvWatch` and quiesced.

- `CsvFileAccepted(originalPath: Path, outputPath: Path)` — a file passed validation and was moved to _Output Directory_. If a name collision would occur, resolve by appending a unique suffix to the destination filename before moving.

- `CsvFileRejected(originalPath: Path, quarantineFilePath: Path, reportPath: Path)` — a file failed validation or could not be read; it was moved to _Quarantine Directory_ with a _Rejection Report_.

- `CsvWatchFault(reason: String)` — a non-recoverable configuration or filesystem error (for example missing _Watch Directory_); you stop automatic processing until the next `StartCsvWatch`.

## Workflow

1. Upon `StartCsvWatch`, validate that _Watch Directory_, _Output Directory_, and _Quarantine Directory_ exist and are readable/writable as required; if not, send `CsvWatchFault` with a clear reason and stop. Otherwise send `CsvWatchStarted` with the effective configuration snapshot.

2. Until `StopCsvWatch`, repeatedly discover candidate files:
    1. On each tick separated by `pollIntervalMs`, list files in _Watch Directory_ (and subdirectories only if `recursive` is true) whose names end with `.csv` (case-insensitive) and which are regular files not currently open for writing by another process (best-effort; if detection is uncertain, skip the file until a later tick).
    2. Ignore files you have already fully processed in this watch session (track by canonical path).

3. For each newly discovered file, process it exactly once:
    1. Open the file and read the first physical line as the header row, using a CSV-aware parse that respects quoted fields. If the file is empty, unreadable, or has no parseable header, treat as invalid and go to step 3.4.
    2. Split the header into field names per CSV rules; trim leading and trailing ASCII whitespace on each name. Compare the resulting sequence to _Expected Column Names_. If they are equal element-wise, the schema is valid; otherwise it is invalid.
    3. If valid, atomically move the file into _Output Directory_ (same basename unless collision handling applies), then send `CsvFileAccepted` with source and destination paths.
    4. If invalid, write a _Rejection Report_ in _Quarantine Directory_ (unique name derived from the source basename plus `.rejection.txt`), then move the CSV next to that report (or move CSV first then write report—either order is acceptable if both end in _Quarantine Directory_), then send `CsvFileRejected` with all three paths. The report must state the failure category and, when applicable, the expected versus actual headers.

4. Upon `StopCsvWatch`, complete any in-progress step 3 for the current file, then send `CsvWatchStopped` and remain idle until the next `StartCsvWatch`.
