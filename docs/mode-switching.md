# Car display selection

The ordinary launcher stays on the music player. Settings → Car display selects:

* **Bluetooth stereo**: no LibreAuto projector starts. Music uses the ordinary Android/Bluetooth output.
* **LibreAuto car display**: projection becomes available through its configuration page. Choosing this mode does not start an encoder, connection server, wake lock, location worker or audio decoder.
* **Official Android Auto**: LibreAuto projection is stopped and its entry points are disabled. Reconnect the car to let official Android Auto connect.

A saved choice is a preference, not proof of an actual head-unit connection. Queue routing uses the actual projection session state, not the preference. Switching stops projection first, waits for teardown, then stops Bluetooth playback and waits for its audio output to release. It never creates an idle service just to send it a stop command. Mode changes do not resume music automatically.

The mode controller leaves the selected mode saved when the optional Android Auto helper is unavailable. The result says **manual action required**, rather than claiming the Google app was paused or enabled. A simulator or manually configured head unit can still use LibreAuto mode. This setting is not a universal replacement/default-projection API and does not by itself make an arbitrary car accept this experimental projector.

Android requires a foreground-start acknowledgement even when a stale queued service start is rejected. The rejected service therefore posts a minimal, deferred foreground notification and immediately removes it and stops. This operating-system handshake starts no projection, location, decoder, transport, wake lock or connection monitor. The acknowledgement is shared by `onCreate` and its first `onStartCommand`; a later distinct foreground-start request is acknowledged separately. It leaves no idle notification or service.

## Optional automatic Android Auto switching

An ordinary Android app cannot enable or disable a different package. For users who want to prevent official Android Auto from claiming the head unit first, the optional helper uses [Shizuku](https://shizuku.rikka.app/guide/setup/) in **ADB-shell mode**, without root.

1. Install the official Shizuku app and start it using wireless debugging (Android 11+) or a computer.
2. In LibreAuto's Car display settings, request Shizuku access and approve it.
3. Enable automatic Android Auto switching, then select the desired mode.

Shizuku's authorization is broader than this implementation. This implementation's short-lived helper accepts only enable-state reads and compare-and-set writes for the fixed `com.google.android.projection.gearhead` package. There is no arbitrary command, package argument or shell string. The helper starts only for a phone mode-change action or a separately opted-in paired-car event, and is removed afterwards. There is no idle polling or helper service. Root/Sui mode is not supported by this integration.

Selecting LibreAuto with automatic switching enabled records Android Auto's exact original enable setting **before** pausing it. Selecting Official Android Auto or Bluetooth restores only a change LibreAuto made. If Android Auto was already disabled, LibreAuto does not claim ownership or enable it later. If a user or the system changed the setting afterwards, restoration preserves the newer setting. The restore journal survives app/process/phone restarts; an interrupted change is checked on the next explicit switch. App launch and status queries never alter Android Auto.

Turning automatic switching off must be followed by applying the current mode through the controller; this restores any owned Android Auto change. If Shizuku is unavailable during restoration, the UI reports that restoration is pending. Restart Shizuku and select Official Android Auto or Bluetooth again. Alternatively, open Android's Android Auto app settings and enable it manually. Do that before uninstalling LibreAuto if its helper paused Android Auto. Clearing LibreAuto's app data erases its restore journal, so restore first.

ADB/wireless-debugging Shizuku normally needs restarting after every phone reboot. No root is required. Android/OEM package restrictions can still reject an operation; the UI must report the failure. A completed package-state change also does not prove that a particular head unit has accepted a new connection. Disconnect and reconnect while parked; this is not a live dashboard hot-swap.

Official Android Auto must stay installed for the current installed-AA compatibility path. Pausing its package is experimental and must be checked against that path on real hardware; LibreAuto never uninstalls it, clears its data or removes saved car associations.

## Integration contract

`CarDisplayController.Runtime` stop callbacks return `true` only after actual resource teardown. `selectMode` serializes transitions, keeps projection gated during the transition and reports bounded failures. The saved mode changes only after both stop callbacks succeed. It exposes `addListener`/`removeListener` so the host can synchronize its USB entry component and refresh UI after mode/transition changes. Listener callbacks and results run on the main thread.

The projection host uses `CarDisplayController.isProjectionAllowed(context)` in addition to its actual session/playback ownership gate. It must stop VLC and await its teardown before starting projection. The primary player must consult `ProjectionIntegration.isSessionActive()` before choosing a projected output adapter; a saved LibreAuto mode alone must never redirect audio into an absent head unit.

The ordinary music player's application ID and signing identity remain unchanged, preserving playlists, content URI grants and established settings. These mode/helper preferences use new preference files and default to official Android Auto with automatic switching off. The player remains available over Bluetooth when the car has no projection support. A separate preferred-projector key survives per-car Bluetooth-only overrides.

## Acceptance boundary

Local policy tests cover default selection, exact restoration, externally modified states and malformed journals. Build/permission/physical-device checks, successful Shizuku transactions, installed-AA identity while paused, USB/wireless car competition and the post-reboot fallback are separate acceptance checks. Do not equate policy tests with those hardware results.
