# Sheno

- Use Kotlin, Jetpack Compose, Material 3, MVI and Room. Keep data local and the app account-free.
- Sheno records user-entered information and schedules. Do not add medical advice, dose recommendations, symptom interpretation or drug identification.
- Preserve the approved Sheno symbol and indigo brand color. Medication shapes and their colors are configurable visual labels.
- Use the shared schedule model for Today, reminders and widgets. Keep calendar-day schedules anchored to the chosen start date.
- Quantity values use integer thousandths. Keep logging and inventory updates in one transaction. Duplicate scheduled actions must be idempotent.
- Verify restore/import without replaying intake deductions. Never ship destructive Room migrations.
- Keep dev and production packages, notification intents and widget intents independent.
- Run unit tests, Android lint, a debug build and relevant emulator tests. Follow docs/RELEASING.md for GitHub releases and signing.
- Bump sheno.versionName and sheno.versionCode in gradle.properties for later releases.
