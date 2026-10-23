// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.identity

/** Fixed user-facing messages only: never exception text, paths or material. */
enum class IdentityStatus(val message: String) {
    READY("Installed Android Auto compatibility checks passed. Car acceptance still needs testing."),
    PREPARING("Checking installed Android Auto compatibility…"),
    SETUP_REQUIRED("Enable installed Android Auto compatibility in LibreAuto before connecting."),
    BUSY("A compatibility check or previous connection is still finishing."),
    CANCELLED("Compatibility setup was stopped."),
    TIMED_OUT("Compatibility setup took too long. Try again."),
    WRONG_THREAD("Compatibility setup could not start safely. Try again."),
    PACKAGE_UNAVAILABLE("Install Android Auto before using this compatibility mode."),
    UNSUPPORTED_VERSION("This installed Android Auto version is not supported by this LibreAuto build."),
    SIGNER_MISMATCH("The installed Android Auto signature is not supported."),
    APK_NOT_READABLE("Android did not allow this app to read the installed Android Auto package."),
    PACKAGE_CHANGED("Android Auto changed during setup. Try connecting again."),
    INPUT_BOUNDS("The installed Android Auto package is outside the supported size limits."),
    APK_HASH_MISMATCH("This Android Auto package variant is not supported by this LibreAuto build."),
    ZIP_INVALID("The installed Android Auto package could not be verified."),
    DEX_BINDINGS_INVALID("This Android Auto package layout is not supported."),
    DERIVATION_FAILED("The installed compatibility identity could not be prepared."),
    CERTIFICATE_DATE_INVALID("The installed compatibility certificate is outside its valid dates."),
    CERTIFICATE_CHAIN_INVALID("The installed compatibility certificate chain could not be verified."),
    KEY_MISMATCH("The installed compatibility identity did not pass verification."),
    KEYSTORE_FAILED("The in-memory compatibility identity could not be initialized."),
    SERVER_ALIAS_FAILED("The installed identity is not usable by this TLS server."),
    CHECK_FAILED("Compatibility setup could not finish. Try again."),
    CLEANUP_UNVERIFIED("Connection cleanup is unfinished. Compatibility identity remains held; reconnect is blocked.")
}
