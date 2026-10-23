# LibreAuto car display module

This Android library preserves the production OpenAuto Lite dashboard, transport,
adaptive frame cadence, offline navigation and session-scoped compatibility code.
The host application owns the launcher, branding, saved library and car-display mode.
It remains an experimental phone-side projector; desktop tests do not prove real-car
or wireless auto-connect compatibility.

The library declares Android 8 (API 26) compatibility so the bundled music player
remains installable on older phones. Its projection setup, USB launch, preview and
service entry points require Android 12L (API 32). No projection component is started
by the host's application initialization, the music screen, boot or a periodic worker.
The separate offline dictation model add-on is not bundled.

## Host integration and ownership

Install `ProjectionIntegration.delegate` in the host Application. This only installs
callbacks; it creates no dashboard, decoder, encoder, sockets or location monitor.

- `isProjectionSelected()` controls availability of the setup UI and optional USB
  alias. It can remain true while the music player is playing.
- `prepareToStart(context, onReady)` releases and joins the host's player ownership
  asynchronously. Its completion is checked again before starting the service.
- `isProjectionAllowed()` is the final mode/audio-owner gate. Its default is false.
- `onProjectionStarting()` runs after the service's exclusive lifetime is acquired,
  and after integration reserves its session, but before foreground promotion, call
  monitoring, wake locks, transport negotiation, rendering or encoding.
- `onProjectionStopped()` runs after successful codec/socket/dashboard cleanup,
  service destruction and exclusive-owner release. If cleanup fails, ownership stays
  reserved and playback must not resume.
- `requestStop(context)` only stops an existing service. It never starts or binds.
  Use `isSessionActive()`/the stopped callback to wait for actual cleanup.
- `syncUsbEntry(context)` enables `org.openandroidauto.UsbProjectionEntry` only when
  the host selected projection on a supported phone. It is disabled in the manifest
  by default; the setup Activity is internal and there is no second launcher.

The service checks the final gate independently in `onCreate`, `onStartCommand` and
the session body. Stale USB intents and in-app starts therefore cannot acquire a wake
lock, monitor, transport or encoder in Bluetooth/official Android Auto mode. Messaging
retains no text unless a live projection session selected that message source.

Original preference keys, transport/package identifiers, installed-AA verification,
protocol behavior and upstream license provenance are retained. See `docs/` and the
source-adjacent README/provenance files. The host manifest owns the app label, theme,
backup policy and sole music-player launcher.
