package org.openandroidauto.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal
import org.junit.Assert.*
import org.junit.Test

class AliasKeyManagerTest {
    private val selected = "development-v2"
    private val old = "development-v1"

    @Test
    fun `server selection pins the configured alias even when older key is first`() {
        val delegate = FakeKeyManager()
        val manager = AliasKeyManager(delegate, selected)

        assertArrayEquals(arrayOf(selected), manager.getServerAliases("RSA", null))
        assertEquals(selected, manager.chooseServerAlias("RSA", null, null))
        assertEquals(selected, manager.chooseEngineServerAlias("RSA", null, null))
        assertEquals(0, delegate.selectionCalls)
    }

    @Test
    fun `unsupported key types and delegate rejection never select an alias`() {
        val delegate = FakeKeyManager()
        val manager = AliasKeyManager(delegate, selected)

        assertNull(manager.getServerAliases("EC", null))
        assertNull(manager.chooseServerAlias("EC", null, null))
        assertNull(manager.chooseEngineServerAlias("EC", null, null))
        assertNull(manager.chooseServerAlias(null, null, null))
        delegate.aliases = arrayOf(old)
        assertNull(manager.getServerAliases("RSA", null))
        assertNull(manager.chooseServerAlias("RSA", null, null))
        assertNull(manager.chooseEngineServerAlias("RSA", null, null))
        delegate.aliases = null
        assertNull(manager.getServerAliases("RSA", null))
    }

    @Test
    fun `issuer constraints reach the delegate unchanged for both server paths`() {
        val delegate = FakeKeyManager()
        val manager = AliasKeyManager(delegate, selected)
        val rejected = arrayOf<Principal>(X500Principal("CN=Rejected Test Issuer"))

        assertNull(manager.chooseServerAlias("RSA", rejected, null))
        assertSame(rejected, delegate.lastIssuers)
        assertNull(manager.chooseEngineServerAlias("RSA", rejected, null))
        assertSame(rejected, delegate.lastIssuers)
        assertNull(manager.getServerAliases("RSA", rejected))
        assertSame(rejected, delegate.lastIssuers)
    }

    @Test
    fun `foreign and null aliases cannot access key or certificate material`() {
        val delegate = FakeKeyManager()
        val manager = AliasKeyManager(delegate, selected)

        assertNull(manager.getPrivateKey(old))
        assertNull(manager.getPrivateKey(null))
        assertNull(manager.getCertificateChain(old))
        assertNull(manager.getCertificateChain(null))
        assertEquals(0, delegate.keyReads)
        assertEquals(0, delegate.chainReads)
        assertSame(delegate.opaqueKey, manager.getPrivateKey(selected))
        assertEquals(1, delegate.keyReads)
        assertArrayEquals(delegate.chain, manager.getCertificateChain(selected))
        assertEquals(1, delegate.chainReads)
        assertEquals(0, delegate.encodedReads)
    }

    @Test
    fun `opaque private key stays opaque and certificate array is defensive`() {
        val delegate = FakeKeyManager()
        val manager = AliasKeyManager(delegate, selected)

        assertSame(delegate.opaqueKey, manager.getPrivateKey(selected))
        assertNotSame(delegate.chain, manager.getCertificateChain(selected))
        assertEquals(0, delegate.encodedReads)
        delegate.chain = null
        assertNull(manager.getCertificateChain(selected))
    }

    @Test
    fun `all client identity paths are disabled without querying delegate`() {
        val delegate = FakeKeyManager()
        val manager = AliasKeyManager(delegate, selected)

        assertNull(manager.getClientAliases("RSA", null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.chooseEngineClientAlias(arrayOf("RSA"), null, null))
        assertEquals(0, delegate.clientCalls)
    }

    @Test
    fun `blank aliases are rejected without delegate access`() {
        for (alias in listOf("", " ", "\t\n")) {
            val delegate = FakeKeyManager()
            try {
                AliasKeyManager(delegate, alias)
                fail("Blank alias accepted")
            } catch (_: IllegalArgumentException) {
                assertEquals(0, delegate.keyReads)
                assertEquals(0, delegate.chainReads)
            }
        }
    }

    private inner class FakeKeyManager : X509KeyManager {
        var aliases: Array<String>? = arrayOf(old, selected)
        var lastIssuers: Array<out Principal>? = null
        var chain: Array<X509Certificate>? = emptyArray()
        var selectionCalls = 0
        var keyReads = 0
        var chainReads = 0
        var clientCalls = 0
        var encodedReads = 0
        val opaqueKey: PrivateKey = object : PrivateKey {
            override fun getAlgorithm(): String = "RSA"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray? {
                encodedReads++
                throw AssertionError("Opaque key must never be exported")
            }
        }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
            lastIssuers = issuers
            return if (keyType == "RSA" && issuers.isNullOrEmpty()) aliases else null
        }

        override fun chooseServerAlias(
            keyType: String?, issuers: Array<out Principal>?, socket: Socket?,
        ): String? {
            selectionCalls++
            return old
        }

        override fun getPrivateKey(alias: String?): PrivateKey? {
            keyReads++
            return opaqueKey
        }

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
            chainReads++
            return chain
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
            clientCalls++
            return arrayOf(old)
        }

        override fun chooseClientAlias(
            keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?,
        ): String? {
            clientCalls++
            return old
        }
    }
}
