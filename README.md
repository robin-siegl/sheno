<p align="center"><img src="branding/logo-indigo.svg" width="100" alt="Sheno logo"></p>
<h1 align="center">Sheno</h1>
<p align="center">Medication schedules, reminders and stock in one place.</p>

Sheno is a native Android app built with Kotlin, Jetpack Compose and Material 3. It keeps a personal medication list, records intakes, and reminds you about your entered schedule and low stock. It does not provide medical advice or choose a medication, dose or schedule.

## Features

- Today’s schedule with Taken, Skip and Undo actions
- Daily, selected-weekday, every-N-days and as-needed schedules
- Medication shapes and colors, including two-color capsules
- Separate strength text and intake quantity, with fractional quantities
- Package refills, stock corrections and configurable low-stock alerts
- Optional ordering website per medication, with copy buttons for name and dosage text
- Intake history and reversible records
- Local Room database, encrypted Android system backup support, and JSON export/import
- Light and dark modes, Material You colors, and a discreet notification/widget option
- A home-screen widget with today’s record and the next scheduled medication

## Get the app

APKs are distributed through [GitHub Releases](https://github.com/robin-siegl/sheno/releases). Sheno is not published on Google Play.

For development builds, open [Actions](https://github.com/robin-siegl/sheno/actions), choose a successful **Checks** run, and download the **sheno-dev** artifact. Extract the APK before installing it. Dev builds have an inverted icon, use a separate package, and keep their own data.

## Reminder setup

In Sheno’s Settings, enable notifications, allow precise reminder timing, and send a test notification. Delivery follows Android notification channels, Do Not Disturb, battery settings and permissions. A force-stopped app needs to be opened again; a powered-off phone cannot deliver an alert.

Schedules follow the phone’s local time. Review the schedule when changing time zones. Unconfirmed entries remain unconfirmed: Sheno does not deduct inventory until an intake is recorded as taken. If the recorded inventory becomes negative, update the stock count. These numbers are user-maintained estimates.

## Ordering

Save the website you normally use to request or order medication. Its low-stock notification can open that link. Sheno supplies copy buttons for the medication name, strength or custom dosage text; you paste the selected text into the website yourself. Personal details stay within the website’s normal form.

The browser toolbar is a prototype built with Android Custom Tabs. Toolbar support varies by browser, so the same copy actions are also available in the medication details screen. Sheno does not submit orders or automatically fill arbitrary websites.

## Backup and restore

Android can include Sheno’s database and settings in encrypted system backups when device backup is enabled. The system controls backup timing. Keep the same production signing key so installed updates and restores remain compatible.

Manual export creates a JSON snapshot containing medication details, stock, history and settings. Import validates the file and shows a preview. It adds missing medications and their associated history; existing medications and current settings are retained. Importing the same snapshot again does not duplicate records or deduct inventory again. To restore a full snapshot, import into an empty installation of Sheno.

## Build

Open the project in Android Studio, or use JDK 17 and Android SDK 36:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

See [release and signing instructions](docs/RELEASING.md), [architecture](docs/ARCHITECTURE.md), and the [roadmap](docs/ROADMAP.md).
