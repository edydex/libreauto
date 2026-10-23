# LibreAuto 0.3.0-preview.1 — test record

Checked on 2026-10-21. Package `org.bluetoothauto`, versionCode **20**, ARM64,
minimum Android **8.0 / API 26**. Projection entry points require **Android 12L /
API 32**. The compatible signed APK is **75,750,537 bytes**.

APK SHA-256: `2495cf654bca25f4b444b05c36d69e0d5e8ee1749702db7a83441f3524cef072`.

## Build and artifact checks

- **1,512 JVM tests** passed: 848 player/integration and 664 projection tests;
  zero failures, errors or skips. Both modules' Android lint passed with zero
  errors (79 player warnings and 189 projection warnings remain).
- **18 Python migration-tool tests** passed. Isolated packaging fixtures also
  verified allowlisted source/docs, exact receipts and hashes, explicit screenshot
  selection, rejection of keys/symlinks/mismatched receipts, and no overwrites.
- APK signature verified with the existing BluetoothAuto signer:
  `aa6d91b1bbeff93b792bd38440721fc0f38392c2795253e0c0e2630545bfd414`.
  Manifest/package/version checks passed. Four ARM64 native libraries retain
  16 KB ELF load alignment; APK ZIP alignment verification passed. A physical
  16 KB-page Android runtime was not tested.
- The installed Pixel APK was read back independently and has the exact hash above.
  Source packaging excludes device evidence, identities, credentials, signing keys,
  SDK paths, caches and build outputs.

## Spare Pixel 6a acceptance

The final APK was installed as an update, without uninstalling BluetoothAuto.
The original **149-track queue**, playlists, folder references and player settings
remain. All original player preference keys compare equal to the pre-upgrade
backup after testing; native checks also verify URI grants and saved-place state.
The first upgrade preserved all three original preference files byte for byte.

**55 native merge assertions passed on this exact final APK.** They exercised:

- Player-first launch and lazy adapters; no projection starts merely by opening
  the app or choosing its mode.
- Real paused VLC ownership, mode switching, and shutdown before a projected
  decoder may acquire the shared queue.
- Preferred LibreAuto projection surviving automatic official/Bluetooth car-mode
  overrides; exact original preferences restored after the fixture.
- A direct stale foreground-service start being rejected without a persistent
  service, encoder frame, decoder, transport worker or wake lock.
- Local installed-Android-Auto compatibility/identity verification, without
  reporting or packaging credentials.

The separate native connection-monitor check read the installed Google provider:
**NOT_PROJECTED, confirmed known**. A fake update broadcast with projection extras
triggered a fresh provider query but could not spoof an active connection. Closing
its last observer removed the receiver and cleared the cache. Pure policy tests
cover suspending Bluetooth navigation work during confirmed official projection;
an actual official Android Auto car connection was not exercised in this session.

The optional Shizuku helper was approved and tested on the spare Pixel. Selecting
LibreAuto paused official Android Auto from its default enabled state to
DISABLED_USER; leaving LibreAuto restored the original state and cleared the
restore journal. Installed-AA compatibility remained ready while its package was
paused. The helper runs only for a mode transition and exits afterwards. The
phone was left with **official Android Auto selected and enabled**, optional
Shizuku switching configured, and car-profile automation off.

The optional legacy OpenAuto Lite migration was tested separately: five missing
settings were imported without replacing existing values. Its original installed
APK was backed up and restored, then independently hash-verified. Its separate
app data was retained. Offline download files and Android permission grants are
not imported by this tool.

## Real desktop projection

The exact final APK connected to Google's installed desktop head-unit simulator
at **800 × 480 / 160 dpi** using the current installed-AA compatibility path.
TLS 1.2 negotiation verified successfully; decoded map and music frames were
visually inspected. The Music tab showed **Track 1 of 149**, Play advanced playback,
and Pause stopped progress. While playing, the projection service owned audio and
**BluetoothAutoService was absent**. Disconnect stopped the session.

An earlier candidate also verified that Play on the phone controlled the projected
queue without starting a second Bluetooth player. The final host-launch behavior
was rechecked after opening projection setup: launching LibreAuto returns to its
music player. No assistant, microphone capture, SMS or phone call was invoked for
these merge checks.

## Cleanup and acceptance limits

Temporary test APK and simulator forwarding were removed. The final idle check
found no player/projection service. Sleep timeout remains **300000 ms**, and
stay-awake-while-plugged-in remains **0**. No GPS simulation was started in this
session. Existing app permissions and the approved Shizuku permission were retained.

These acceptance checks were recorded on the local preview before GitHub
publication; the release retains the exact APK hash above. GitHub build checks
validate source separately and do not replace device acceptance. No physical-car
wireless competition/timing test, older-Android hardware run, post-reboot Shizuku
recovery run, full-drive offline download regression, or new HiBreak performance
benchmark is claimed. Car profiles have policy/event-lifetime tests but still need
real-car acceptance. Bundling adds roughly 2 MB over the preceding BluetoothAuto
APK; file size is not evidence of battery or frame-time equivalence. Idle ownership
and shutdown were verified directly; performance under real driving remains a
separate measurement.

## GitHub source publication checks — 2026-10-22

The initial repository adds build automation, contribution/release guides and
packaging/report-validation tests. App/projection source, APK assets and vendored
routing code remain byte-identical to the source shipped with the tested APK.
The local Android build, **1,512 JVM tests** and both lint tasks passed again;
the expanded Python tool suite passed **33 tests**. GitHub Actions validates the
source independently; its reports are available on the repository's Actions page.
CI uses a disposable signer and does not publish a replacement for this APK.
