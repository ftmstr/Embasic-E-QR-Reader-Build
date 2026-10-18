# Embasic E-QR Reader — Public Build

Android/Kotlin QR and barcode reader under active development. This is an **independent public source snapshot**, not a mirror of the private repository or its Git history.

## Existing source (not release-verified)
- Compose + Material 3 application with Tara / Geçmiş / Oluştur screens
- CameraX preview, bundled ML Kit decoding, ZXing-C++ fallback
- QR generator, payload parsing, local URL risk signals and user-confirmed actions
- Room history, Private Scan controls, DataStore settings
- Synthetic JVM tests and source-security checks
- Google Ads/UMP consent integration in the existing development source; no production AdMob keys are included

**Critical rule:** scanned URLs and Android external actions must never launch without user involvement. Do not log raw payloads. Private Scan must not save results to history.

## Build
Requirements: JDK 17+, Gradle 9.5.0, Android SDK Platform 37, SDK Build Tools 36.0.0. The source branch contains no Gradle wrapper JAR. CI installs Gradle independently.

```bash
python3 tools/check_public_source.py
python3 tools/check_source_sanity.py
python3 tools/check_history_search_sql.py
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug
```

The debug-signed development APK, **only after a successful CI run**, will be available in GitHub Actions artifacts as `app-debug.apk`. This repository does not produce Play Store signed releases.

## Verification gates
- Android test, lint, compile and APK artifact: **not verified at bootstrap**.
- Device camera and gallery behavior: **not verified**.
- Test methods are authored but are not proof that they passed.
- Dependency graph, license notices and vulnerability review remain a separate release gate.
- Never ship with sample advertisement IDs or unapproved production configuration.

See [public release gates](docs/PUBLIC-RELEASE-GATES.md) and [third-party notices](docs/THIRD-PARTY-NOTICES.md).

## Licensing
No open-source license has been granted for this application's own code. Public visibility alone is not a grant of rights. Third-party components remain subject to their respective licenses and SDK terms.
