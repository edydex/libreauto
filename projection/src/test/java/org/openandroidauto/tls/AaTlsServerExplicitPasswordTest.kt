package org.openandroidauto.tls

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.KeyStore

/** Generated JVM software-store identities only. No official app material. */
class AaTlsServerExplicitPasswordTest {
    companion object {
        private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
        private val certificate by lazy { TestCertHelper.generateSelfSignedCert(pair) }
        private val legacyField = AaTlsServer::class.java.getDeclaredField("privateIdentityPassword").apply { isAccessible = true }
    }

    @Test fun `independent explicit passwords coexist and leave legacy state untouched`() {
        val a = "synthetic-alpha".toCharArray(); val b = "synthetic-beta".toCharArray()
        val aBefore = a.copyOf(); val bBefore = b.copyOf()
        withLegacy("unrelated-legacy".toCharArray()) { legacy ->
            val first = AaTlsServer(store(a), a)
            val second = AaTlsServer(store(b), b)
            assertFalse(first.createEngine().useClientMode)
            assertFalse(second.createEngine().useClientMode)
            assertSame(legacy, legacyField.get(null))
            assertArrayEquals("unrelated-legacy".toCharArray(), legacy)
            assertArrayEquals(aBefore, a); assertArrayEquals(bBefore, b)
        }
        a.fill('\u0000'); b.fill('\u0000'); aBefore.fill('\u0000'); bBefore.fill('\u0000')
    }

    @Test fun `wrong explicit password refuses without mutating caller or legacy password`() {
        val actual = "synthetic-correct".toCharArray(); val wrong = "synthetic-wrong".toCharArray()
        val before = wrong.copyOf()
        withLegacy(actual.copyOf()) { legacy ->
            val input = store(actual)
            assertThrows(Exception::class.java) { AaTlsServer(input, wrong) }
            assertSame(legacy, legacyField.get(null)); assertArrayEquals(actual, legacy)
            assertArrayEquals(before, wrong)
        }
        actual.fill('\u0000'); wrong.fill('\u0000'); before.fill('\u0000')
    }

    @Test fun `explicit null does not fall back to a correct legacy password`() {
        val actual = "synthetic-nonempty".toCharArray()
        withLegacy(actual.copyOf()) { legacy ->
            val input = store(actual)
            assertThrows(Exception::class.java) { AaTlsServer(input, null) }
            assertSame(legacy, legacyField.get(null)); assertArrayEquals(actual, legacy)
        }
        actual.fill('\u0000')
    }

    @Test fun `one argument constructor keeps generated software fixture compatibility`() {
        withLegacy(null) {
            val password = "androidauto".toCharArray()
            val engine = AaTlsServer(store(password)).createEngine()
            assertFalse(engine.useClientMode)
            assertArrayEquals(arrayOf("TLSv1.2"), engine.enabledProtocols)
            assertNull(legacyField.get(null))
            password.fill('\u0000')
        }
    }

    @Test fun `one argument constructor retains legacy import consumption and wiping`() {
        val password = "synthetic-legacy-import".toCharArray()
        withLegacy(password.copyOf()) { legacy ->
            val engine = AaTlsServer(store(password)).createEngine()
            assertFalse(engine.useClientMode)
            assertNull(legacyField.get(null))
            assertTrue(legacy!!.all { it == '\u0000' })
        }
        password.fill('\u0000')
    }

    private fun store(password: CharArray): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, password)
        setKeyEntry("synthetic-password-test", pair.private, password, arrayOf(certificate))
    }
    private fun withLegacy(value: CharArray?, block: (CharArray?) -> Unit) {
        synchronized(AaTlsServer::class.java) {
            val previous = legacyField.get(null)
            legacyField.set(null, value)
            try { block(value) }
            finally { legacyField.set(null, previous); value?.fill('\u0000') }
        }
    }
}
