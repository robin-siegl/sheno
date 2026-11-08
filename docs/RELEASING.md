# Releasing Sheno

## Dev APK

Run **Checks** from the Actions tab and download the `sheno-dev` artifact. Extract the APK before installing. The dev package `dev.robinsiegl.sheno.dev` installs alongside the production app.

## One-time signing setup

Use the JDK bundled with Android Studio if `keytool` is unavailable in PowerShell. From a folder outside the checkout, run:

```powershell
keytool -genkeypair -v -keystore sheno-release.p12 -storetype PKCS12 -alias sheno -keyalg RSA -keysize 3072 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\sheno-release.p12).Path)) | Set-Clipboard
```

Add the following repository Actions secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 text from the clipboard |
| `ANDROID_KEYSTORE_PASSWORD` | Password entered when creating the keystore |
| `ANDROID_KEY_ALIAS` | `sheno` |
| `ANDROID_KEY_PASSWORD` | The same password for this PKCS12 keystore |

Keep an offline copy of the keystore and passwords. Production updates use the same signing key.

## Production APK

Update `sheno.versionName` and increment `sheno.versionCode` in `gradle.properties`. Merge the version change to `main`, then run **Production APK** on `main`. With publication enabled, the workflow creates a version tag and attaches the verified APK and checksum to the GitHub Release.

## Protect main

After **Checks** has run successfully, import `.github/main-ruleset.json` under **Settings → Rules → Rulesets → New ruleset → Import a ruleset**. It requires pull requests, passing checks, and squash merges. It blocks deletion and force pushes, with no mandatory reviewer count for the solo repository.
