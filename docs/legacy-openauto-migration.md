# Bringing settings from the separate OpenAuto Lite app

Updating BluetoothAuto to LibreAuto keeps its Android package ID, signing identity,
playlists, folders, current music queue, existing file access and player settings.
No migration command is needed for that upgrade.

The previously separate `org.openandroidauto.lite` app has its own Android sandbox.
LibreAuto cannot automatically read that app's private preferences. The optional
local tool below can bring selected settings across when both installed builds
allow Android's `run-as` debugging access. It does not require root or Shizuku.
If the old installation is a nondebuggable release build, the tool stops with a
clear explanation before reading its preferences or changing either app. It does
not replace that installation, bypass Android's sandbox, or silently omit data.
Installing a matching debug build temporarily is a separate developer recovery
procedure; preserve the exact original APK and verify its signer before doing so.

Use Python 3.9+, Android platform-tools ADB, and Android SDK `apksigner`. The tool
finds ADB and `apksigner` on PATH or in common SDK locations. Use `--adb PATH` or
`--apksigner PATH` when needed. The phone must have debugging enabled and authorize
the computer. Install the unified LibreAuto APK first; the tool checks its package,
version code and compatible signing certificate before proceeding.

From the LibreAuto source directory, first review the read-only counts:

```sh
python3 tools/migrate_openauto.py
```

Apply while no car connection or playback is needed:

```sh
python3 tools/migrate_openauto.py --apply --backup-dir "$HOME/LibreAuto-private-backups"
```

The apply step force-stops **LibreAuto only** so cached preferences cannot overwrite
the import. It leaves LibreAuto closed; open it normally afterward. Add
`--serial DEVICE_SERIAL` when more than one debugging device is connected. A
`--expected-signer SHA256` override supports deliberately self-signed builds; do
not bypass signer verification to import into an unidentified APK.

The migration copies only missing keys from an explicit allowlist: projection
video/audio choices, navigation providers and units, call/SMS opt-in settings,
supported messaging-app selections, installed-Android-Auto compatibility opt-in,
and the selected/trusted Bluetooth car. Runtime permissions and special-access
grants still belong to Android and must be granted to LibreAuto separately.

Saved destinations are appended to the shared list, preserving current entries
and their order. Existing coordinates keep the current name and metadata. Home
and Work keep their current assignments; an imported conflicting shortcut becomes
an ordinary saved place. The list holds 50 saved places. Overflow remains in the
old app and is reported as a count. Recent places and travel history are not
imported.

It does not import the old Lite music queue or URI grants, overwrite existing
player settings/playlists, change the selected car-display mode, copy switching
helper state, import connection reports, or start any service. The old app is
unchanged. Running the import again fills only still-missing settings and places.

Optional backups contain private preference XML, including saved locations and
device selections. They use restricted local file permissions and must stay out
of source control and release assets. The tool rejects backup destinations inside
the source tree or workspace release outputs. Preference files changed during a
failed apply are restored from their original in-memory bytes; a failed rollback
is reported explicitly. Do not open LibreAuto during migration.

This tool does not copy downloaded maps, offline routing graphs or address-index
files. Download those again in LibreAuto if needed. Removing LibreAuto through
Android app settings also removes its data; installing an APK update preserves it.

To undo a successful import, keep LibreAuto closed and restore only the changed
files from the private `libreauto-before` backup using the same app's debugging
identity. Files absent before import need removing rather than an empty overwrite.
The old OpenAuto Lite installation remains available as the unchanged source.

Pure fixture checks, requiring no phone:

```sh
python3 -m unittest discover -s tools/tests -p 'test_migrate_openauto.py' -v
```
