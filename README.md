# LibreAuto 0.3.0-preview.1

[Download the Android preview](https://github.com/edydex/libreauto/releases/tag/v0.3.0-preview.1)
· [Build checks](https://github.com/edydex/libreauto/actions/workflows/checks.yml)
· [Report a problem](https://github.com/edydex/libreauto/issues)

One Android app for local music, navigation hints on a Bluetooth stereo, and an
optional OpenAuto dashboard on a compatible car screen. The music player is the
main screen; **Settings → Car display** holds the projection controls.

This preview combines BluetoothAuto and OpenAuto Lite. Its Android package stays
`org.bluetoothauto`, version code **20**, so a compatible update keeps existing
playlists, folders, queue, file access, saved places and player settings.

## Install or upgrade

Download `LibreAuto-0.3.0-preview.1-arm64.apk` from the release linked above.
The release includes checksums and corresponding source. This is an experimental
preview; see [the tested devices and remaining checks](TESTED.md).

The supplied ARM64 APK supports **Android 8.0+** for the music player. Car-screen
projection currently requires **Android 12L+**. Stock VLC is not required; the
player includes LibVLC's engine.

Install the APK over BluetoothAuto using Android's update prompt. **Do not
uninstall the old app first.** With an authorized debugging connection:

```sh
adb install -r LibreAuto-0.3.0-preview.1-arm64.apk
```

Android must accept the signing certificate as an update. A self-built APK often
has a different signer; use the compatible supplied APK to preserve an existing
installation. Uninstalling or clearing app data removes its saved library and
settings. The source package intentionally contains no signing key.

The old, separate OpenAuto Lite app has its own sandbox. An optional, local,
debug-build migration tool can import selected settings and saved places without
overwriting current choices. It does not copy offline downloads or Android
permission grants. See [migration instructions](docs/legacy-openauto-migration.md).

## Start with Bluetooth music

1. Pair the phone and stereo normally, then select Bluetooth Audio on the stereo.
2. Open **Music library**. Create a playlist, add individual files, import a local
   VLC playlist, or link a music folder through Android's file picker.
3. Play a playlist or folder in order or shuffled. Hold a playlist or track's
   **⋮** button and drag to reorder it; tap the button for other actions.
4. Use the main screen's map icon to open the navigator chosen in Settings.
   Google Maps is separate; Open maps provides the built-in option.
5. Allow location and playback notifications when requested. For Google Maps
   guidance or supported message notifications, enable LibreAuto's notification
   access in Android's settings.

Navigation hints can appear in the **song title**, **album artwork**, or **both**.
Artwork support and refresh behavior depend on the stereo. Music files remain
unchanged. Removing a playlist or linked folder never deletes the original audio.
The library supports 64 playlists, 32 linked folders and 512 tracks per playlist.
Local M3U/M3U8, XSPF and PLS import is supported; internet playlist streams are not.
The [retained player reference](docs/bluetoothauto-player-reference.md) explains
the existing music and artwork controls in more detail.

## Choose the car-screen mode

Open **Settings → Car display**:

| Mode | Behavior |
| --- | --- |
| Bluetooth stereo | Use the player without a car-screen projector. LibreAuto projection stays off. |
| LibreAuto car display | Enables the optional dashboard. Open **OpenAuto setup** to configure and connect. |
| Official Android Auto | Preferred by default when installed. Stops LibreAuto projection and leaves the car screen to official Android Auto. |

With a stereo that has no Android Auto support, the ordinary Bluetooth player is the fallback.
The music player also remains usable with a plain Bluetooth stereo while the
preferred projector is official Android Auto; there is no automatic claim that
LibreAuto can detect whether a particular stereo supports projection.

Selecting a mode alone starts no encoder, connection server, decoder, location
worker or monitoring service. A connection hands audio ownership from the player
to projection only after the old output has stopped. Playlists, the selected
queue, saved destinations and units are shared; Bluetooth and projected audio
retain separate output engines. The unused engine remains stopped.

While music is running, the player observes official Android Auto's reported
connection state. Confirmed official projection suspends Bluetooth navigation
artwork, turn matching, GPS updates and spoken cues; ordinary music stays usable.
The observer stops with the player and uses no periodic polling. See
[connection detection](docs/official-projection-monitor.md).

The optional dashboard includes built-in navigation, supported message
notifications/replies, calls and music. Its navigation setup also contains
offline area downloads, local routing and address search for supported downloaded
coverage. The Bluetooth player's Open maps screen retains its existing online
routing/prepared-map pipeline; combining the apps does not make that screen a
new offline router.

Production-car compatibility is experimental. Keep official Android Auto
installed for the current optional compatibility path, which supports the pinned
**17.8.663874** variant and rejects unsupported versions. Updating Android Auto
may require a compatibility update. A selected mode or accepted service start is
not proof of a working head-unit connection. Change modes while parked and
reconnect the car. Read [mode switching](docs/mode-switching.md) and the exact
build's [test record](TESTED.md).

## Optional automatic switching

**Remember a mode for each car** lets you select an already-paired Bluetooth car
and opt into applying its mode when it connects. It does not scan, pair devices,
identify cars from Wi-Fi, poll in the background or start music automatically.
Manual choices and existing verified sessions take priority. See
[paired-car profiles](docs/car-profiles.md).

To pause official Android Auto when LibreAuto should own the screen, enable the
separate **Pause official Android Auto in LibreAuto mode** option. This uses
approved [Shizuku](https://shizuku.rikka.app/guide/setup/) in ADB-shell mode,
without root. It saves Android Auto's previous enable state and restores only
LibreAuto's own change when leaving that mode or turning the option off.
Debugging-based Shizuku normally needs restarting after a phone reboot. Without
the helper, LibreAuto still controls its own projector and explains any manual
Android Auto setup needed; it does not pretend the other app was switched.

## Stop or undo

To keep only the music player, select **Bluetooth stereo**, turn car profiles
off, and stop any active connection. To return to Google's dashboard, select
**Official Android Auto**, then reconnect the car.

Before uninstalling, turn profiles off and restore official Android Auto if the
helper paused it. Keep Shizuku running until restoration succeeds; if it is
unavailable, the app reports a pending restore. You can also enable Android Auto
manually in Android's app settings. Then stop the music session, turn LibreAuto
off in notification-access settings and uninstall. Original music files and the
separately installed Google Maps/Android Auto apps are not deleted.

## Privacy, scope and development

Music, playlists, saved places and car profiles stay on the phone. Online maps,
search and routing disclose the requested area, search text and route points to
their configured services: OpenFreeMap, Photon, Valhalla and, for supported
Google-turn matching, Overpass. Google Maps has its own data handling. Optional
speech uses Android's installed TTS service and its selected voice settings.

This bundle excludes the earlier full prototype's assistant/accessibility
experiments and speech recognition model. The desktop Lab remains a separate
development tool. Google Maps notification interpretation currently targets
English and may fall back to a simple turn arrow when a map match is ambiguous.
No general production-car or performance superiority claim is made by bundling.

See [building and packaging](docs/BUILDING.md), [provenance](UPSTREAM.md) and
[LICENSE](LICENSE). `TESTED.md` distinguishes local checks, phone checks and
remaining real-car checks for the exact packaged APK. LibreAuto is independent
of Google, VideoLAN and car manufacturers.

Development starts with [CONTRIBUTING.md](CONTRIBUTING.md). The separate
[HiBreak Auto Scale](https://github.com/edydex/hibreak-auto-scale) project fixes
official Android Auto scaling on supported Bigme firmware; it is not this app.
