package org.openandroidauto.tls

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class InBandTlsTest {

    private fun createTestKeyStore(): KeyStore {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()
        val cert = TestCertHelper.generateSelfSignedCert(keyPair)
        ks.setKeyEntry("aa_server_cert", keyPair.private, "androidauto".toCharArray(), arrayOf(cert))
        return ks
    }

    @Test
    fun `full in-band TLS handshake between client and server`() {
        val serverKs = createTestKeyStore()
        val server = AaTlsServer(serverKs)
        val inBandTls = InBandTls(server.createEngine())

        val clientCtx = SSLContext.getInstance("TLSv1.2")
        clientCtx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), null)
        val clientEngine = clientCtx.createSSLEngine().apply { useClientMode = true }
        clientEngine.beginHandshake()

        val netBuf = java.nio.ByteBuffer.allocate(32768)
        val appBuf = java.nio.ByteBuffer.allocate(32768)
        val empty = java.nio.ByteBuffer.allocate(0)

        // Client Hello
        clientEngine.wrap(empty, netBuf)
        netBuf.flip()
        val clientHello = ByteArray(netBuf.remaining())
        netBuf.get(clientHello)

        // Feed to InBandTls
        assertFalse(inBandTls.isHandshakeComplete)
        val responses = inBandTls.beginHandshake()
        val responses2 = inBandTls.feedHandshakeData(clientHello)
        val allResponses = responses + responses2
        assertTrue(allResponses.isNotEmpty())

        // Feed server responses to client
        for (record in allResponses) {
            netBuf.clear()
            netBuf.put(record)
            netBuf.flip()
            while (netBuf.hasRemaining()) {
                appBuf.clear()
                clientEngine.unwrap(netBuf, appBuf)
                var task = clientEngine.delegatedTask
                while (task != null) { task.run(); task = clientEngine.delegatedTask }
            }
        }

        // Client should need to wrap (send key exchange)
        netBuf.clear()
        while (clientEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            clientEngine.wrap(empty, netBuf)
        }
        netBuf.flip()
        val clientFinish = ByteArray(netBuf.remaining())
        netBuf.get(clientFinish)

        // Feed client finish to server
        val finalResponses = inBandTls.feedHandshakeData(clientFinish)
        assertTrue(inBandTls.isHandshakeComplete)
        // Complete the client side using the server's final flight.
        for (record in finalResponses) {
            netBuf.clear(); netBuf.put(record); netBuf.flip()
            while (netBuf.hasRemaining()) {
                appBuf.clear()
                val result = clientEngine.unwrap(netBuf, appBuf)
                var task = clientEngine.delegatedTask
                while (task != null) { task.run(); task = clientEngine.delegatedTask }
                assertNotEquals(SSLEngineResult.Status.BUFFER_UNDERFLOW, result.status)
            }
        }
        assertEquals(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, clientEngine.handshakeStatus)
        // Real bidirectional application records, at our writer's maximum chunk size.
        val plaintext = ByteArray(12_000) { (it % 251).toByte() }
        val encrypted = inBandTls.encrypt(plaintext)
        appBuf.clear()
        val received = clientEngine.unwrap(java.nio.ByteBuffer.wrap(encrypted), appBuf)
        assertEquals(SSLEngineResult.Status.OK, received.status)
        appBuf.flip(); val decoded = ByteArray(appBuf.remaining()); appBuf.get(decoded)
        assertArrayEquals(plaintext, decoded)
        netBuf.clear()
        val sent = clientEngine.wrap(java.nio.ByteBuffer.wrap(plaintext), netBuf)
        assertEquals(plaintext.size, sent.bytesConsumed())
        netBuf.flip(); val clientRecord = ByteArray(netBuf.remaining()); netBuf.get(clientRecord)
        assertArrayEquals(plaintext, inBandTls.decrypt(clientRecord))
    }

    @Test
    fun `encryption refuses an incomplete handshake`() {
        val serverKs = createTestKeyStore()
        val server = AaTlsServer(serverKs)
        val inBandTls = InBandTls(server.createEngine())

        // No plaintext fallback is allowed when the peer has not authenticated.
        assertFalse(inBandTls.isHandshakeComplete)
        val plain = "hello".toByteArray()
        assertThrows(IllegalStateException::class.java) { inBandTls.encrypt(plain) }
        assertThrows(IllegalStateException::class.java) { inBandTls.decrypt(plain) }
    }
}
