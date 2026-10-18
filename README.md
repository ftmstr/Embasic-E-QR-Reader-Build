# Embasic E-QR Reader — Public Android Build

**Status:** Independent, sanitized Android build workspace. This repository contains no history from the private development repository.

## Product
Privacy-focused Android QR/barcode reader, interpreter, and generator. Scanned links and external actions always require an explicit user action. QR payloads must not be logged. Private Scan must not persist history.

## Technology
Kotlin, Jetpack Compose, Material 3, CameraX, bundled ML Kit Barcode Scanning, ZXing-C++ fallback, Room and DataStore. The canonical 1.0 product specification remains outside this public repository.

## Build instructions
The Android application is introduced through a reviewable bootstrap pull request. After it is merged, see the Gradle and GitHub Actions workflow for exact build commands.

## Security and release status
- No production signing keys, user scan data, personal account configuration, or private Git commit history.
- Debug artifacts are **test builds only**, never a signed Play Store release.
- Source code may be published before Android build verification; existence of source is not proof of a functioning APK.
- Full dependency license inventory and real-device validation remain release gates.

## License
No open-source license has been granted for this source repository. Third-party notices, where applicable, are tracked separately. Public visibility does not imply permission to reuse or redistribute source code.
