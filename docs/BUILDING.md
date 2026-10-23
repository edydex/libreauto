# Building and packaging LibreAuto

Use JDK 17, Android SDK platform 36 and build-tools 35.0.1. Gradle 8.13 is pinned
by the included wrapper, Android Gradle Plugin is 8.13.0 and Kotlin is 2.2.20.
Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` or a private `local.properties` SDK path.
The initial build downloads dependencies from the configured official repositories.

From the source directory:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :projection:testDebugUnitTest :app:lintDebug :projection:lintDebug
python3 -m unittest discover -s tools/tests -v
```

On Windows use `gradlew.bat`. Python 3.10+ is needed for the local tools, not for
the Android app. The default APK is ARM64; select another supported LibVLC ABI
with `-PbluetoothAutoAbi=armeabi-v7a` or another ABI supplied by the dependency.
Projection defaults to adaptive rendering with a 30 fps cap; the comparison
build setting is `-PopenAutoMapFrameMode=fixed15`.

The app's minimum SDK stays 26 (Android 8.0). Projection entry points require
SDK 32 (Android 12L) before acquiring session resources. Do not raise the app's
minimum SDK merely to silence projection API checks. The player and projector
have separate resource owners and output engines; a mode preference does not
mean an active projection session.

Debug signing uses the developer machine's Android debug key, which is not
distributed. Keep the same key for your own update series. A new local debug
key will not update an existing APK signed with the project's compatible key.
Use a matching signed update rather than uninstalling a phone's data-bearing
installation to bypass a signature error.

GitHub Actions builds the source and runs tests/lint with a disposable debug
signer. It uploads check reports, not a compatible release update. Download the
maintainer's tested APK from [Releases](https://github.com/edydex/libreauto/releases)
to update an existing installation. The repository contains no signing secret.

## Release staging

The packager consumes an already-built APK. It does not build, install, contact
the phone, invent acceptance evidence or publish a release. Run final checks
against the exact final APK after all source and asset changes; record the real
outcomes in `TESTED.md`.

Create a private final audit JSON with this schema. Every APK hash must be the
same lowercase SHA-256 value obtained from the final artifact, installed copy
and tested artifact. `required_checks_passed` means the required checks actually
passed; remaining acceptance limits must still be described in `TESTED.md`.

```json
{
  "schema": "libreauto-release-audit-v1",
  "final": true,
  "required_checks_passed": true,
  "package": "org.bluetoothauto",
  "version_name": "0.3.0-preview.1",
  "version_code": 20,
  "apk_sha256": "<64 lowercase hexadecimal characters>",
  "installed_apk_sha256": "<same final APK hash>",
  "tested_apk_sha256": "<same final APK hash>",
  "signer_sha256": "aa6d91b1bbeff93b792bd38440721fc0f38392c2795253e0c0e2630545bfd414"
}
```

Package after that evidence exists:

```sh
python3 tools/package_libreauto.py --audit-receipt evidence/release-audit.json
```

`--apk PATH` selects an explicit APK. `--output-root PATH` selects a release
parent directory; the default is the workspace's `outputs` directory. The tool
creates a new `LibreAuto-0.3.0-preview.1` directory and refuses to overwrite one.
Optional public screenshots require explicit paths, for example:

```sh
python3 tools/package_libreauto.py --audit-receipt evidence/release-audit.json --screenshot evidence/public-player.png=Player.png
```

Review screenshots for private locations, contacts and notifications before
passing them. No screenshot is selected automatically. The private receipt is
not distributed.

The output contains the APK, `LibreAuto-source.zip`, README, LICENSE, UPSTREAM,
TESTED, an explicitly allowlisted public `docs` folder and `SHA256SUMS.txt`, plus
only explicitly selected screenshots. The outer README's guide links therefore
work without extracting the source ZIP. The
source ZIP includes both modules' source, Gradle wrapper/configuration, vendored
third-party source/notices, docs and tool tests. It excludes private evidence,
build outputs, caches, machine SDK paths, signing keys and compiled app/library
artifacts. Each source member has a SHA-256 entry and the wrapper retains its
executable permission. The packager reopens the ZIP and verifies its contents.

Desktop Lab builds and old assistant experiments are separate projects. Their
artifacts, phone backups, downloaded offline map data and developer credentials
do not belong in this source archive.
