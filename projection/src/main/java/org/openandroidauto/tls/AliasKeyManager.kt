package org.openandroidauto.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** Restricts a server key manager to one configured identity without exporting its key. */
internal class AliasKeyManager(
    private val delegate: X509KeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {
    init {
        require(alias.isNotBlank()) { "TLS server alias must not be blank" }
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (supports(keyType, issuers)) arrayOf(alias) else null

    override fun chooseServerAlias(
        keyType: String?, issuers: Array<out Principal>?, socket: Socket?,
    ): String? = if (supports(keyType, issuers)) alias else null

    override fun chooseEngineServerAlias(
        keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?,
    ): String? = if (supports(keyType, issuers)) alias else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) delegate.getPrivateKey(alias) else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) delegate.getCertificateChain(alias)?.clone() else null

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseClientAlias(
        keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?,
    ): String? = null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?,
    ): String? = null

    private fun supports(keyType: String?, issuers: Array<out Principal>?): Boolean =
        keyType != null && delegate.getServerAliases(keyType, issuers)?.contains(alias) == true
}
