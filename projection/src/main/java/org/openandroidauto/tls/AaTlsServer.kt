package org.openandroidauto.tls

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Calendar
import javax.security.auth.x500.X500Principal
import javax.net.ssl.*

/** TLS1.2 server using an installation-specific development identity.
 * No Google Automotive Link key or certificate is included in this project.
 * A production receiver can reject the development identity after TLS.
 */
class AaTlsServer private constructor(
    private val keyStore: KeyStore,
    private val passwordSelection: PasswordSelection
) {
    /** Existing behavior retained for current callers, including legacy import state. */
    constructor(keyStore: KeyStore) : this(keyStore, PasswordSelection.Legacy)

    /** Per-instance password selection. Explicit null means null, with no legacy
     * fallback. The caller keeps ownership of its array; only our private copy is
     * wiped after KeyManagerFactory initialization (also on initialization failure). */
    constructor(keyStore: KeyStore, entryPassword: CharArray?) :
        this(keyStore, PasswordSelection.Explicit(entryPassword?.copyOf()))

    private sealed class PasswordSelection {
        object Legacy : PasswordSelection()
        class Explicit(var ownedCopy: CharArray?) : PasswordSelection()
    }

    companion object {
        // Keep earlier app-generated keys intact; TLS must select this corrected policy explicitly.
        private const val ALIAS = "open-auto-development-tls-v2"
        private const val KEY_PASSWORD = "androidauto"
        private var privateIdentityPassword: CharArray? = null

        fun createKeyStore(context: Context): KeyStore {
            // Private developer-provided identity, never bundled or copied by this project.
            val identity = java.io.File(context.filesDir, "development/identity.p12")
            if (identity.isFile && (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                val passwordFile = java.io.File(context.filesDir, "development/identity.password")
                val password = if (passwordFile.isFile) passwordFile.readText().trimEnd().toCharArray() else charArrayOf()
                val imported = KeyStore.getInstance("PKCS12").apply { identity.inputStream().use { load(it, password) } }
                privateIdentityPassword = password
                return imported
            }
            val providerStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!providerStore.containsAlias(ALIAS)) {
                val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
                val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    // Conscrypt hashes/pads TLS messages before its delegated raw-RSA operation.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=Open Auto Development,O=Open Auto"))
                    .setCertificateSerialNumber(BigInteger(120, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(start.time).setCertificateNotAfter(end.time)
                    .build()
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(spec); generateKeyPair()
                }
            }
            return providerStore
        }
    }

    private val sslContext: SSLContext = createSslContext()
    private fun createSslContext(): SSLContext {
        val kmf = when (val selection = passwordSelection) {
            is PasswordSelection.Explicit -> {
                // This branch never reads or changes the legacy companion state.
                try {
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                        init(keyStore, selection.ownedCopy)
                    }
                } finally {
                    selection.ownedCopy?.fill('\u0000')
                    selection.ownedCopy = null
                }
            }
            PasswordSelection.Legacy -> {
                // Preserve the one-argument constructor's existing behavior.
                val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                // AndroidKeyStore keys do not have a password. Synthetic JVM fixtures do.
                val password = when {
                    keyStore.type == "AndroidKeyStore" -> null
                    privateIdentityPassword != null -> privateIdentityPassword
                    else -> KEY_PASSWORD.toCharArray()
                }
                try { factory.init(keyStore, password) } finally {
                    privateIdentityPassword?.fill('\u0000'); privateIdentityPassword = null
                }
                factory
            }
        }
        val permissiveDevelopmentTrust = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val managers = if (keyStore.type == "AndroidKeyStore") kmf.keyManagers.map { manager ->
            if (manager is X509KeyManager) AliasKeyManager(manager, ALIAS) else manager
        }.toTypedArray() else kmf.keyManagers
        return SSLContext.getInstance("TLSv1.2").apply { init(managers, permissiveDevelopmentTrust, SecureRandom()) }
    }
    fun createEngine(): SSLEngine = sslContext.createSSLEngine().apply {
        useClientMode = false
        wantClientAuth = true
        needClientAuth = false
        enabledProtocols = arrayOf("TLSv1.2")
    }
}
