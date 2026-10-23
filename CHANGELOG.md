# Changelog

## 0.3.0-preview.1

First unified LibreAuto preview, combining BluetoothAuto 0.2.0-preview.18 and
OpenAuto Lite in the existing `org.bluetoothauto` package.

- Music player as the main screen; optional projected dashboard in Settings.
- Shared playlists, queue, saved/recent destinations and units, with one active
  audio owner and lazy projection workers.
- Official Android Auto preferred by default, ordinary Bluetooth playback for
  other stereos, and optional paired-car overrides.
- Optional Shizuku switch pauses official Android Auto for LibreAuto projection
  and restores only LibreAuto's own change.
- Actual official Android Auto connection monitoring suspends redundant Bluetooth
  navigation work during confirmed projection.
- Retains Android 8+ player support; projection requires Android 12L+.
- Optional, bounded import of selected settings from the separate OpenAuto Lite
  debug app without replacing current choices.

See [TESTED.md](TESTED.md) for the exact APK hash, Pixel/simulator checks and
remaining real-car and hardware acceptance limits.
