# Contributing to LibreAuto

LibreAuto keeps local music playback, Bluetooth navigation artwork and optional
car-screen projection in one Android app. Keep the player useful on Android 8+
and acquire projection resources only on supported phones during an actual
connection. The music player remains the launcher; projection setup lives in
Settings → Car display.

## Build and check

Use JDK 17, Android SDK platform 36 and build-tools 35.0.1. See
[BUILDING.md](docs/BUILDING.md) for complete instructions. From a fresh clone:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :projection:testDebugUnitTest :app:lintDebug :projection:lintDebug
python3 -m unittest discover -s tools/tests -v
```

On Windows use `gradlew.bat` and a configured Python 3.10+ interpreter. Set a
local Android SDK path using `ANDROID_HOME` or ignored `local.properties`.
The initial build needs internet access to obtain the pinned dependencies.

GitHub Actions runs these source checks. It uses a disposable debug signing key;
its build is not the compatible APK distributed in Releases. No private signing
key, Google identity or connected phone is required for source checks. CI cannot
establish a successful car connection, safe driving behavior or hardware performance.

## Changes and tests

- Keep mode selection separate from verified session ownership. Stop the previous
  audio/transport owner before starting another; do not leave idle encoders,
  decoders, GPS subscriptions or connection polling running.
- Preserve `org.bluetoothauto`, existing preferences, playlists and persisted file
  access. Do not require users to uninstall a data-bearing app to test an update.
- Cover changed parsing, persistence and lifecycle contracts with meaningful
  tests. Distinguish pure policy tests, phone checks, simulator checks and real-car
  acceptance in reports.
- Keep UI controls understandable and make map attribution readable. Retain
  upstream source, license and copyright notices; record dependency changes in
  [UPSTREAM.md](UPSTREAM.md).

## Reports and private material

For a bug report, include app version, phone/Android version, selected car-display
mode, wired/wireless/Bluetooth connection, reproduction steps and expected behavior.
Sanitize screenshots and logs. Do not include contacts, message text, real trip
history, device identities, pairing codes, user media, keys or phone backups.

`private/`, `evidence/`, generated builds, local SDK paths and signing material
stay outside Git. The release packager uses an additional explicit source allowlist.
The desktop Lab and earlier assistant experiments are separate projects and are
not included in this repository.

## Releases

The maintainer publishes a signed, tested APK and matching SHA-256 checksums.
Keep the established signer for updates, preserve published assets and use a new
version/code for a changed APK. Record exact-artifact acceptance in `TESTED.md`;
follow [release staging](docs/BUILDING.md#release-staging). Never present a new CI
build as the previously device-tested artifact.
