# Installed Android Auto compatibility

This optional mode uses the installed Android Auto package as a local
compatibility dependency. It is off by default. The app checks the exact supported
version, signing certificate, full base APK and its reviewed data layout before
preparing an identity in memory. It writes no credential files, stores no
extracted identity, executes no code from that package and provides no fallback
when verification fails.

The current source profile supports the reviewed Android Auto **17.8.663874**
base-package variant only. Other versions or variants may be refused even if they
are genuine. An Android Auto update can require a compatibility update to Open
Auto. The module does not download, modify or disable Android Auto.

## Set up

1. Install Open Auto and keep Android Auto installed normally.
2. Open **Car compatibility** and select **Use installed Android Auto compatibility**.
3. Press **Check installed Android Auto**. A successful check confirms local
   compatibility verification; it does not connect to a receiver or prove car support.
4. Grant only the normal connection/location/assistant access needed for the
   features you choose, then start a connection using Open Auto's ordinary controls.

An unsupported version, signature, unreadable package or failed check stops this
mode with a fixed explanatory message. There is no imported/generated credential
fallback in the release path. Private debug experiments may retain a separate
development path when this mode is off; that is not the release setup workflow.

## Lifetime and privacy

Preparation is bounded and runs on a worker. Its ZIP/DEX, certificate, DER and
mixing scratch is wiped, and its APK descriptor is closed, before the long
projection session starts. Only required TLS material remains session-local.

Stopping closes real connection resources and waits for actual session/TLS work,
video/PCM workers and dashboard cleanup. Only then is the memory entry deleted,
key destruction attempted and owned password cleared. Cancellation and deadlines
do not prove that a native provider stopped. Unverified cleanup keeps the session
blocked rather than reusing or prematurely releasing its identity. JCA providers
can retain internal copies; perfect memory erasure is not claimed.

There is a bounded setup/connected-handshake deadline, and no whole-drive timer.
The provider does no periodic polling while a drive is active. The production
identity module writes no lab report or credential cache. Provenance and third
party licensing are recorded in
[the identity module](../app/src/main/java/org/openandroidauto/identity/IDENTITY-PROVENANCE.md).

## Undo

Press **Stop connection** and wait for shutdown to finish, then clear **Use
installed Android Auto compatibility**. No extracted credential file needs to be
removed. Uninstalling Open Auto removes its own settings/data; it does not remove
or reconfigure Android Auto, paired cars or unrelated apps. Assistant permissions
and optional offline-model removal have their own setup/undo instructions.

## What remains to test

The preceding private callback-local identity experiment passed authentication
and full projection with the official desktop head-unit simulator. This new
normal source path still requires fresh release-build and device acceptance.

Stock-car authentication and wireless operation are not established by simulator
success. Wireless bootstrap, supported protocol variants, normal Android Wi-Fi
approval and competition with official Android Auto's own automatic connection
remain separate parked-car tests. HiBreak compatibility with its installed Android
Auto version must be verified separately. No CPU, RAM or battery advantage over
official Android Auto is claimed.
