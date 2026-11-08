# Architecture

Sheno uses unidirectional state flow. Compose screens emit `AppIntent` values. `ShenoViewModel` coordinates operations and exposes an immutable `Model` through StateFlow. The repository owns database transactions; screens do not write database records directly.

`Domain.kt` contains schedule calculation, input validation, stock forecasts and backup models. Strength is descriptive text and is never used to calculate dose. Quantities use integer thousandths of a unit, avoiding floating-point inventory drift. No clinical calculations or recommendations are implemented.

Room stores medication snapshots, immutable intake records, snoozes and notification receipts. Taken/Skip actions are keyed by medication, local date and scheduled time. Recording a slot twice is idempotent. Stock deduction and intake logging occur in one transaction. Undo restores the recorded quantity, even if the medication’s current dose has changed. Editing descriptive details preserves concurrent stock changes.

AlarmManager schedules the next reminder with exact timing when permitted, otherwise with an explicitly disclosed inexact fallback. Broadcast receivers rebuild scheduling after restart, package replacement, time changes and permission grants. WorkManager provides periodic maintenance and days-based stock checks. Snoozes and delivery receipts are persisted. Notification actions use explicit, package-specific PendingIntents. Tapping a notification never automatically records an intake.

Schedules use wall-clock local dates and times. Java time resolves nonexistent spring times forward, and uses the earlier offset for an ambiguous autumn time. The occurrence key remains stable so a repeated local hour cannot create two records. Intervals are every N calendar days anchored to the user’s start date; hourly and complex cycle schedules are future work.

Manual import validates the entire envelope before a transaction. Snapshot inventory is imported as-is and history is not replayed as new deductions. Existing medication IDs are skipped, including their incoming history, to avoid merging a stock snapshot from a different point in time.

The ordering helper opens a browser Custom Tab. Its toolbar copies user-entered medication details; a callback never reads the browser form. Clipboard contents are marked sensitive. Android keyboard extensions, Accessibility Services, account storage and order submission are not part of this implementation.
