# Installed Android Auto compatibility identity

This GPL-3.0-or-later module is an explicit, default-off, version-specific local
interoperability provider. It contains no certificate, encrypted-key/salt payload,
private key, APK, executable Google code, or private capture. It never serializes
credentials, copies an APK or executes foreign code. It reads only the installed
package through the current ordinary app UID.

`InstalledAaIdentityPolicy` pins the reviewed Android Auto 17.8.663874 base APK,
version code and installed signing-certificate digest. PackageManager provides
version/signers; the complete APK digest is checked over the same bounded input
used for ZIP/DEX parsing before any derivation. Reviewed DEX byte-array headers,
duplicate copies and certificate string-table bindings are checked. Unsupported
versions/variants/signers fail closed. Updating Android Auto may require an Open
Auto compatibility update; no generated, imported or cached identity fallback is
used by this provider.

The derivation is adapted under GPL-3.0-or-later from
[mretallack/AndroidAuto tools/ExtractKey.java, commit
366ee5fee58f7ddeee7f824ff167853b5ea1d0cf](https://github.com/mretallack/AndroidAuto/blob/366ee5fee58f7ddeee7f824ff167853b5ea1d0cf/tools/ExtractKey.java).
The upstream hardcoded public CA is not copied: exact PEM seed bytes are read
from the pinned installed APK. The 48-byte tweak and seven intentionally aliased
mixing rounds follow that GPL implementation. The bounded AOSP Base64 decoder is
credited in `AospSkipBase64.java`, Apache-2.0, Copyright 2010 The Android Open
Source Project; primary source:
[AOSP android.util.Base64](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/util/Base64.java).
Existing GPL/Apache license notices apply.

Preparation verifies dates, issuer/CA constraints, signatures and PKIX with the
embedded CA as explicit local trust anchor; revocation is not checked. Random
challenge signatures verify key correspondence and the first X509 key manager's
actual RSA server-alias selection. This is consistency verification, not receiver
trust or endorsement.

The preparation transaction returns only a closeable session-local TLS material.
Before it returns, its parser/DEX/PEM/DER/mixing/challenge buffers are wiped and
its frame exits. The Android reader rechecks file/package metadata and closes the
APK input before the long session starts. Only required KeyStore/private-key and
random in-memory entry password survive. No store/factory is placed in a global
registry, Intent, report or persistent file. TLS uses an explicit per-instance
password. The factory can be consumed only once.

`SessionIdentityLease` encloses both the real session and its finalizer. A durable
stop event requests real socket close and cancellation; it then joins actual
structured work and requires affirmative cleanup proof. No drive-duration cap or
polling is used. Failed/unproven cleanup retains ownership without periodic CPU
wakeups. Preparation/handshake deadlines are logical cooperative bounds, not
permission to destroy material used by a blocked VM, kernel or crypto provider.
Only after actual joined cleanup does terminal close delete the memory entry,
attempt key destruction and wipe/drop the password. JCA/provider/spec objects can
retain internal copies until garbage collection; perfect erasure is not claimed.

Checks run only at explicit check/connection start. No credentials are cached;
an installed-package change is checked again before first TLS use and on the
next connection. This module does not stop/disable official Android Auto, alter
Bluetooth/Wi-Fi settings, establish stock-car support or provide a wireless
bootstrap. Those remain separate acceptance requirements.
