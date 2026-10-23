# Paired-car profiles

Projection is preferred by default. Official Android Auto handles compatible cars
without a profile, while the music player remains available for Bluetooth stereos.
Profiles are optional overrides, not a required per-car setup questionnaire.

Open **Settings → Car display → Remember a mode for each car** and add an already paired Bluetooth car.
It immediately keeps the saved LibreAuto projector preference on supported phones,
or prefers official Android Auto otherwise. **Change mode** optionally selects another
projector or Bluetooth-only operation. Enable **Apply saved car preferences** to apply
these choices on future connections. The receiver stays disabled until this explicit opt-in
and all required permissions are granted. Adding a profile does not change the current
mode; the next new ACL connection to that exact selected car applies it.

The picker reads paired devices only when opened. It never scans, pairs devices,
enables Bluetooth, infers a car from Wi-Fi, polls connection state or maintains a
foreground monitoring service. Bluetooth stereo never starts playing music by itself.

Each event uses the same session shutdown and optional Android Auto switching
controller as the manual controls. A known car already using Bluetooth stereo does
not interrupt its music merely because it reconnects. Unknown devices leave the mode
unchanged. Duplicate connection broadcasts do not override a manual choice, and a
manual selection pauses automation for the current connection. Connection state and
manual pause survive process recreation; the state resets on a different Android boot.
Two selected cars connected at once require a manual choice.

A live LibreAuto projector or trusted reported official projection is preserved.
An in-progress manual transition also wins over automation. LibreAuto attempts a
wireless connection only after the central transition and audio-owner handoff succeed.
Selecting a mode, requesting a foreground service, and negotiating a ready car
connection are separate states. Background-start restrictions, permission problems,
and unavailable switching helpers produce an actionable, temporary notification.
There is no claim that the connection is ready just because Android accepted its
start request. Android may require opening the connection screen while parked.

The optional Shizuku preference still controls whether official Android Auto is
paused/restored. A stopped or unapproved helper does not become a successful switch.
No additional background-location permission is requested. Profiles using **LibreAuto
car display** require fine location and, on Android 13+, Nearby Wi-Fi, in addition to
Nearby devices and notifications. Profiles using official Android Auto or Bluetooth
stereo require only the applicable Bluetooth/Nearby devices and notification access.
Background location foreground-service promotion may still be refused; the existing
connected-device-only fallback remains in charge of that decision.

Disconnects do not change the selected global mode or another car's session. Only a
wireless session whose exact car address was confirmed by the projection service and
matches an automatic attempt can be stopped by that car's disconnect. USB, TCP and
manual sessions cannot inherit ownership from an unrelated request. Turning profiles
off removes their event receiver and clears automatic ownership without disrupting
the current manual session. Removing a profile does not unpair the phone.

The `CarProfilesTest` JVM cases cover strict bounded serialization, SDK-specific
permissions, unknown devices, duplicate broadcasts, manual pause/reconnect, multiple
cars, process-state restoration, and requested/verified/ready/failed session ownership.
Real head-unit Bluetooth/wireless timing still requires a physical car test.

The preferred projector is saved independently from the current car mode. Manual
projector selection updates that preference; Bluetooth-only overrides and automatic
car events never rewrite it. Fresh preferences choose official Android Auto. The native
merge acceptance also verifies that a LibreAuto preference survives official/Bluetooth
overrides and restores the exact original preference afterward.

The ACL receiver releases its Android broadcast token within eight seconds, exactly
once. Timeout also cancels the event ticket: late shutdown callbacks cannot apply a
new mode or start projection. A helper transaction already in progress still settles
through its durable restore journal; cancellation never invents a successful switch.
