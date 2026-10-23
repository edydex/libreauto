package org.openandroidauto.identity;

import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic owned material only; no official APK/key/certificate fixtures. */
public class InstalledAaMaterialLeaseTest {
    private static final class TestKey implements PrivateKey {
        final AtomicInteger destroyed = new AtomicInteger();
        public String getAlgorithm() { return "RSA"; }
        public String getFormat() { return null; }
        public byte[] getEncoded() { return null; }
        public void destroy() { destroyed.incrementAndGet(); }
    }
    @Test public void oneEngineUseHasTemporaryPasswordCopyAndTerminalCloseIsIdempotent() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
        TestKey key = new TestKey(); char[] password = "owned fixture".toCharArray();
        var material = new InstalledAaIdentityMaterial.Material(store, key, password);
        AtomicReference<char[]> handed = new AtomicReference<>();
        var engine = material.createEngine((actual, copy) -> {
            assertSame(store, actual); assertNotSame(password, copy); assertArrayEquals(password, copy);
            handed.set(copy); return SSLContext.getDefault().createSSLEngine();
        });
        assertNotNull(engine); assertFalse(material.isClosed());
        assertArrayEquals(new char[password.length], handed.get());
        assertThrows(IllegalStateException.class, () -> material.createEngine((s,p) -> engine));
        material.close(); material.close();
        assertTrue(material.isClosed()); assertEquals(1, key.destroyed.get());
        assertArrayEquals(new char[password.length], password);
        assertThrows(IllegalStateException.class, () -> material.createEngine((s,p) -> engine));
    }
    @Test public void failedEngineInitializationCannotRetryAndWipesItsCopy() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
        TestKey key = new TestKey(); char[] password = "fixture".toCharArray();
        var material = new InstalledAaIdentityMaterial.Material(store, key, password);
        AtomicReference<char[]> handed = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> material.createEngine((s,p) -> {
            handed.set(p); throw new IllegalStateException("owned fixture");
        }));
        assertTrue(Arrays.equals(new char[password.length], handed.get()));
        assertThrows(IllegalStateException.class, () -> material.createEngine((s,p) -> null));
        assertFalse(material.isClosed()); material.close(); assertEquals(1, key.destroyed.get());
    }
    @Test public void preparationLeaseRetainsNoParserOrDerivationByteArrays() {
        for (var field : InstalledAaIdentityMaterial.Material.class.getDeclaredFields())
            assertNotEquals("Large parsing buffers must leave before session scope", byte[].class, field.getType());
    }
}
