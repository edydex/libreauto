/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Derivation adapted from mretallack/AndroidAuto tools/ExtractKey.java,
 * commit 366ee5fee58f7ddeee7f824ff167853b5ea1d0cf. See IDENTITY-PROVENANCE.md.
 * No certificate, ciphertext, salt, private key, or foreign executable code is
 * bundled here. Reviewed offset/length bindings apply only to the pinned APK.
 */
package org.openandroidauto.identity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509ExtendedKeyManager;

/** Bounded installed-APK preparation transaction. No foreign code executes.
 * prepare() returns only after all parser/DEX/DER/mixing buffers have been wiped
 * and its preparation frame has exited. The Android caller closes/revalidates
 * the APK input before beginning the long session. Material must stay owned
 * until ALL TLS users have really joined; no timer/cancellation is such proof.
 * JCA providers may retain internal copies; this is best-effort, not perfect erasure. */
public final class InstalledAaIdentityMaterial {
    private InstalledAaIdentityMaterial() {}
    public enum Status {
        VERIFIED, CANCELLED, TIMED_OUT, INPUT_BOUNDS, APK_HASH_MISMATCH, ZIP_INVALID,
        DEX_BINDINGS_INVALID, DERIVATION_FAILED, CERTIFICATE_DATE_INVALID,
        CERTIFICATE_CHAIN_INVALID, KEY_MISMATCH, KEYSTORE_FAILED, SERVER_ALIAS_FAILED
    }
    public enum AbortReason { CANCELLED, TIMED_OUT }
    public static final class Abort extends Exception {
        public final AbortReason reason;
        public Abort(AbortReason reason) { this.reason = reason; }
    }
    public interface Checkpoint { void check() throws Abort; }
    public static final class Refused extends Exception {
        public final Status status;
        Refused(Status status) { this.status = status; }
    }
    public interface EngineFactory { SSLEngine create(KeyStore store, char[] password) throws Exception; }

    /** Only TLS-needed objects survive preparation. No global/static credential state.
     * One engine factory use, and one terminal close after actual session cleanup. */
    public static final class Material implements AutoCloseable {
        private KeyStore store;
        private PrivateKey key;
        private char[] password;
        private boolean consumed, closed;
        Material(KeyStore store, PrivateKey key, char[] password) {
            this.store = store; this.key = key; this.password = password;
        }
        public synchronized SSLEngine createEngine(EngineFactory factory) throws Exception {
            if (closed || consumed) throw new IllegalStateException("Identity lease unavailable");
            consumed = true;
            char[] copy = password.clone();
            try { return factory.create(store, copy); }
            finally { Arrays.fill(copy, '\0'); }
        }
        public synchronized boolean isClosed() { return closed; }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (store != null) try { store.deleteEntry("local-memory-check"); } catch (Exception ignored) {}
            if (key != null) try { key.destroy(); } catch (Exception ignored) {}
            if (password != null) Arrays.fill(password, '\0');
            store = null; key = null; password = null;
        }
    }
    private static final int MAX_PEM_BYTES = 4096;
    private static final int SALT_OFFSET = 3384448, SALT_COPY_OFFSET = 4129036, SALT_BYTES = 256;
    private static final int CIPHER_OFFSET = 3384712, CIPHER_COPY_OFFSET = 4129300, CIPHER_BYTES = 1712;
    // These are string_data_item offsets (ULEB length begins here). The reviewed
    // index records PEM payload offsets two bytes later; the string_ids table binds these items.
    private static final int CERT_A_OFFSET = 7901707, CERT_B_OFFSET = 7904616;

    /** Caller owns/closes the APK source BEFORE using the returned material.
     * All verification is pinned to this same complete bounded input stream. */
    public static Material prepare(InputStream source, long length, Checkpoint check) throws Abort, Refused {
        byte[] dex = null, salt = null, saltCopy = null, encrypted = null, encryptedCopy = null;
        byte[] pemA = null, pemB = null, mixed = null, aes = null, decrypted = null, middle = null, der = null;
        byte[] challenge = null, signature = null, passwordBytes = null;
        char[] password = null;
        PrivateKey privateKey = null;
        KeyStore store = null;
        String alias = "local-memory-check";
        Status failure = Status.DERIVATION_FAILED;
        boolean handedOff = false;
        try {
            check.check();
            if (!InstalledAaIdentityPolicy.apkSizeAllowed(length)) throw new Refused(Status.INPUT_BOUNDS);
            dex = readPinnedPrimaryDex(source, length, check);

            validateDexHeader(dex);
            salt = arrayData(dex, SALT_OFFSET, SALT_BYTES);
            saltCopy = arrayData(dex, SALT_COPY_OFFSET, SALT_BYTES);
            encrypted = arrayData(dex, CIPHER_OFFSET, CIPHER_BYTES);
            encryptedCopy = arrayData(dex, CIPHER_COPY_OFFSET, CIPHER_BYTES);
            if (!Arrays.equals(salt, saltCopy) || !Arrays.equals(encrypted, encryptedCopy))
                throw new Refused(Status.DEX_BINDINGS_INVALID);
            pemA = certificateString(dex, CERT_A_OFFSET);
            pemB = certificateString(dex, CERT_B_OFFSET);

            check.check();
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate a = parseCertificate(factory, pemA), b = parseCertificate(factory, pemB);
            if ((a.getBasicConstraints() >= 0) == (b.getBasicConstraints() >= 0))
                throw new Refused(Status.CERTIFICATE_CHAIN_INVALID);
            X509Certificate leaf = a.getBasicConstraints() < 0 ? a : b;
            X509Certificate ca = a.getBasicConstraints() >= 0 ? a : b;
            byte[] leafPem = leaf == a ? pemA : pemB;
            byte[] caPem = ca == a ? pemA : pemB;
            failure = Status.CERTIFICATE_DATE_INVALID;
            Date now = new Date();
            leaf.checkValidity(now); ca.checkValidity(now);
            check.check();
            failure = Status.CERTIFICATE_CHAIN_INVALID;
            if (!leaf.getIssuerX500Principal().equals(ca.getSubjectX500Principal()) ||
                    !ca.getIssuerX500Principal().equals(ca.getSubjectX500Principal()))
                throw new Refused(failure);
            leaf.verify(ca.getPublicKey());
            ca.verify(ca.getPublicKey());
            PKIXParameters parameters = new PKIXParameters(Collections.singleton(new TrustAnchor(ca, null)));
            parameters.setRevocationEnabled(false); parameters.setDate(now);
            CertPathValidator.getInstance("PKIX").validate(
                    factory.generateCertPath(Collections.singletonList(leaf)), parameters);

            check.check();
            failure = Status.DERIVATION_FAILED;

            mixed = new byte[48];
            tweakBytes(leafPem, mixed, salt, check);
            tweakBytes(caPem, mixed, salt, check);
            for (int i = 0; i < 7; i++) tweakBytes(mixed, mixed, salt, check); // Intentional in-place alias.
            aes = Arrays.copyOf(mixed, 32);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"), new IvParameterSpec(mixed, 32, 16));
            decrypted = cipher.doFinal(encrypted);
            if (decrypted.length < 55 || decrypted.length > CIPHER_BYTES) throw new Refused(failure);
            middle = Arrays.copyOfRange(decrypted, 28, decrypted.length - 26);
            der = AospSkipBase64.decode(middle);
            if (der.length == 0 || der.length > 4096) throw new Refused(failure);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            check.check();
            failure = Status.KEY_MISMATCH;
            if (!(privateKey instanceof RSAPrivateKey) || !(leaf.getPublicKey() instanceof RSAPublicKey) ||
                    !((RSAPrivateKey) privateKey).getModulus().equals(((RSAPublicKey) leaf.getPublicKey()).getModulus()))
                throw new Refused(failure);
            challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            Signature proof = Signature.getInstance("SHA256withRSA");
            proof.initSign(privateKey); proof.update(challenge); signature = proof.sign();
            proof.initVerify(leaf.getPublicKey()); proof.update(challenge);
            if (!proof.verify(signature)) throw new Refused(failure);

            check.check();
            failure = Status.KEYSTORE_FAILED;
            passwordBytes = new byte[32]; new SecureRandom().nextBytes(passwordBytes);
            password = new char[passwordBytes.length];
            for (int i = 0; i < password.length; i++) password[i] = (char) ('!' + ((passwordBytes[i] & 255) % 90));
            store = KeyStore.getInstance("PKCS12"); store.load(null, password);
            store.setKeyEntry(alias, privateKey, password, new java.security.cert.Certificate[]{leaf, ca});
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, password);
            if (managers.getKeyManagers().length == 0) throw new Refused(failure);

            check.check();
            failure = Status.SERVER_ALIAS_FAILED;
            if (!serverAliasMatches(managers.getKeyManagers(), leaf, ca, check)) throw new Refused(failure);
            check.check();
            // Preallocate the lease before changing cleanup ownership. prepare()'s
            // finally runs and releases all scratch BEFORE this return reaches caller.
            Material material = new Material(store, privateKey, password);
            handedOff = true;
            return material;
        } catch (Abort | Refused refused) { throw refused; }
        catch (Exception ignored) { throw new Refused(failure); }
        finally {
            if (!handedOff && store != null) try { store.deleteEntry(alias); } catch (Exception ignored) {}
            if (!handedOff && privateKey != null) try { privateKey.destroy(); } catch (Exception ignored) {}
            wipe(dex); wipe(salt); wipe(saltCopy); wipe(encrypted); wipe(encryptedCopy);
            wipe(pemA); wipe(pemB); wipe(mixed); wipe(aes); wipe(decrypted); wipe(middle);
            wipe(der); wipe(challenge); wipe(signature); wipe(passwordBytes);
            if (!handedOff && password != null) Arrays.fill(password, '\0');
        }
    }

    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }

    /** SSLContext uses the first X509 key manager, not a later manager that happens
     * to offer a suitable alias. Prove its engine selection and the selected key
     * itself, rather than only checking a nonempty manager array or key algorithm. */
    static boolean serverAliasMatches(KeyManager[] managers, X509Certificate leaf,
                                      X509Certificate ca, Checkpoint check) throws Exception {
        X509ExtendedKeyManager selected = null;
        for (KeyManager manager : managers) {
            if (!(manager instanceof X509KeyManager)) continue;
            if (!(manager instanceof X509ExtendedKeyManager)) return false;
            selected = (X509ExtendedKeyManager) manager;
            break;
        }
        if (selected == null) return false;
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(managers, null, new SecureRandom()); // No handshake or network operation.
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false); engine.setEnabledProtocols(new String[]{"TLSv1.2"});
        byte[] challenge = null, signed = null;
        try {
            check.check();
            String alias = selected.chooseEngineServerAlias("RSA", null, engine);
            if (alias == null) return false;
            X509Certificate[] chain = selected.getCertificateChain(alias);
            PrivateKey key = selected.getPrivateKey(alias);
            if (chain == null || chain.length != 2 || !leaf.equals(chain[0]) || !ca.equals(chain[1]) ||
                    !(key instanceof RSAPrivateKey) || !(leaf.getPublicKey() instanceof RSAPublicKey) ||
                    !((RSAPrivateKey) key).getModulus().equals(((RSAPublicKey) leaf.getPublicKey()).getModulus())) return false;
            challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            Signature proof = Signature.getInstance("SHA256withRSA");
            proof.initSign(key); proof.update(challenge); signed = proof.sign();
            proof.initVerify(leaf.getPublicKey()); proof.update(challenge);
            boolean valid = proof.verify(signed);
            check.check();
            return valid;
        } finally {
            if (challenge != null) Arrays.fill(challenge, (byte) 0);
            if (signed != null) Arrays.fill(signed, (byte) 0);
            try { engine.closeOutbound(); } catch (Exception ignored) {}
            try { engine.closeInbound(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readPinnedPrimaryDex(InputStream source, long length, Checkpoint check) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        BoundedDigestInput input = new BoundedDigestInput(source, length, digest, check);
        ZipInputStream zip = new ZipInputStream(input);
        byte[] buffer = new byte[64 * 1024];
        byte[] primary = null;
        long total = 0;
        int entries = 0;
        HashSet<String> names = new HashSet<>();
        boolean valid = false;
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                check.check();
                if (++entries > InstalledAaIdentityPolicy.MAX_ENTRIES ||
                        !InstalledAaIdentityPolicy.entryNameAllowed(entry.getName()) || !names.add(entry.getName()))
                    throw new Refused(Status.ZIP_INVALID);
                boolean want = entry.getName().equals("classes.dex");
                long entryBytes = 0;
                WipingBuffer dex = want ? new WipingBuffer() : null;
                // The only accumulating output is the bounded primary DEX. No APK buffer or temp file.
                try {
                    while (true) {
                        check.check();
                        int count = zip.read(buffer);
                        if (count < 0) break;
                        if (count == 0) throw new Refused(Status.ZIP_INVALID);
                        entryBytes += count; total += count;
                        if (entryBytes > InstalledAaIdentityPolicy.MAX_ENTRY_BYTES ||
                                total > InstalledAaIdentityPolicy.MAX_UNCOMPRESSED_BYTES)
                            throw new Refused(Status.INPUT_BOUNDS);
                        if (want) dex.write(buffer, 0, count);
                    }
                    zip.closeEntry(); // ZIP's CRC/length checks apply to all expanded entries.
                    if (want) primary = dex.toByteArray();
                } finally { if (dex != null) dex.wipe(); }
            }
            // ZipInputStream may prefetch part of the central directory. Those bytes are already
            // digested; consume the remaining raw stream once, without reopening the pathname.
            while (input.read(buffer) >= 0) check.check();
            if (input.count != length) throw new Refused(Status.INPUT_BOUNDS);
            if (!MessageDigest.isEqual(digest.digest(), expectedApkDigest()))
                throw new Refused(Status.APK_HASH_MISMATCH);
            if (primary == null || entries == 0) throw new Refused(Status.ZIP_INVALID);
            valid = true;
            return primary;
        } catch (Abort | Refused failure) { throw failure; }
        catch (ReadAbort stopped) { throw stopped.abort; }
        catch (Exception ignored) { throw new Refused(Status.ZIP_INVALID); }
        finally {
            Arrays.fill(buffer, (byte) 0);
            if (!valid && primary != null) Arrays.fill(primary, (byte) 0);
            try { zip.close(); } catch (Exception ignored) {} // Releases inflater; wrapper close is a no-op.
        }
    }
    private static byte[] expectedApkDigest() {
        String text = InstalledAaIdentityPolicy.APK_SHA256;
        byte[] out = new byte[32];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static final class BoundedDigestInput extends FilterInputStream {
        final long maximum; final MessageDigest digest; final Checkpoint check; long count;
        BoundedDigestInput(InputStream source, long maximum, MessageDigest digest, Checkpoint check) {
            super(source); this.maximum = maximum; this.digest = digest; this.check = check;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            try { check.check(); } catch (Abort abort) { throw new ReadAbort(abort); }
            int n = in.read(bytes, offset, length);
            if (n == 0) throw new IOException();
            if (n > 0) { count += n; if (count > maximum) throw new IOException(); digest.update(bytes, offset, n); }
            return n;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1); return n < 0 ? -1 : one[0] & 255;
        }
        @Override public void close() {} // Caller retains descriptor for final fstat and closes it.
    }
    private static final class WipingBuffer extends ByteArrayOutputStream {
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            byte[] previous = buf;
            super.write(bytes, offset, length);
            if (previous != buf) Arrays.fill(previous, (byte) 0);
        }
        void wipe() { Arrays.fill(buf, (byte) 0); reset(); }
    }
    private static final class ReadAbort extends IOException {
        final Abort abort;
        ReadAbort(Abort abort) { this.abort = abort; }
    }
    static void validateDexHeader(byte[] dex) throws Refused {
        if (dex == null || dex.length < 112 || !InstalledAaIdentityPolicy.dexMagicAllowed(dex) ||
                u32(dex, 32) != dex.length || u32(dex, 36) != 112 || u32(dex, 40) != 0x12345678L)
            throw new Refused(Status.DEX_BINDINGS_INVALID);
    }
    static byte[] arrayData(byte[] dex, int offset, int length) throws Refused {
        if (length <= 0 || length > 4096 || (offset & 3) != 0 || offset < 0 || offset > dex.length - 8 - length ||
                u16(dex, offset) != 0x0300 || u16(dex, offset + 2) != 1 || u32(dex, offset + 4) != length)
            throw new Refused(Status.DEX_BINDINGS_INVALID);
        return Arrays.copyOfRange(dex, offset + 8, offset + 8 + length);
    }
    static byte[] certificateString(byte[] dex, int offset) throws Refused {
        long size = u32(dex, 56), ids = u32(dex, 60);
        if (size > 1_000_000 || ids < 112 || ids > dex.length || size * 4 > dex.length - ids)
            throw new Refused(Status.DEX_BINDINGS_INVALID);
        boolean referenced = false;
        for (int i = 0; i < size; i++) if (u32(dex, (int) ids + i * 4) == offset) { referenced = true; break; }
        if (!referenced || offset < 112 || offset >= dex.length) throw new Refused(Status.DEX_BINDINGS_INVALID);
        int p = offset, utf16 = 0, shift = 0;
        for (int n = 0; ; n++) {
            if (n == 5 || p >= dex.length) throw new Refused(Status.DEX_BINDINGS_INVALID);
            int b = dex[p++] & 255;
            if (n == 4 && (b & 0xf0) != 0) throw new Refused(Status.DEX_BINDINGS_INVALID);
            utf16 |= (b & 127) << shift;
            if ((b & 128) == 0) break;
            shift += 7;
        }
        if (utf16 <= 0 || utf16 > MAX_PEM_BYTES || p > dex.length - utf16 - 1 || dex[p + utf16] != 0)
            throw new Refused(Status.DEX_BINDINGS_INVALID);
        for (int i = p; i < p + utf16; i++) {
            int b = dex[i] & 255;
            if (!(b == '\n' || b == '\r' || b >= 32 && b <= 126)) throw new Refused(Status.DEX_BINDINGS_INVALID);
        }
        byte[] pem = Arrays.copyOfRange(dex, p, p + utf16);
        byte[] begin = "-----BEGIN CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII);
        byte[] end = "-----END CERTIFICATE-----\n".getBytes(StandardCharsets.US_ASCII);
        if (!ends(pem, begin, 0) || !ends(pem, end, pem.length - end.length)) {
            Arrays.fill(pem, (byte) 0); throw new Refused(Status.DEX_BINDINGS_INVALID);
        }
        return pem;
    }
    private static boolean ends(byte[] bytes, byte[] expected, int start) {
        if (start < 0 || start > bytes.length - expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (bytes[start + i] != expected[i]) return false;
        return true;
    }
    private static X509Certificate parseCertificate(CertificateFactory factory, byte[] pem) throws Exception {
        ByteArrayInputStream stream = new ByteArrayInputStream(pem);
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(stream);
        if (stream.available() != 0) throw new Refused(Status.CERTIFICATE_CHAIN_INVALID);
        return certificate;
    }
    static void tweakBytes(byte[] source, byte[] buffer, byte[] salt, Checkpoint check) throws Abort {
        if (source == null || source.length > 4096 || buffer.length != 48 || salt.length != SALT_BYTES)
            throw new IllegalArgumentException();
        for (int i = 0; i < source.length; i++) {
            if ((i & 31) == 0) check.check();
            for (int j = 0; j < 48; j++) {
                int value = buffer[j] & 255;
                buffer[j] = (byte) (((((value >> 7) | (value + value)) + 33) ^ salt[j % salt.length]) ^ source[i]);
            }
        }
    }
    private static int u16(byte[] b, int p) throws Refused {
        if (p < 0 || p > b.length - 2) throw new Refused(Status.DEX_BINDINGS_INVALID);
        return (b[p] & 255) | (b[p + 1] & 255) << 8;
    }
    private static long u32(byte[] b, int p) throws Refused {
        if (p < 0 || p > b.length - 4) throw new Refused(Status.DEX_BINDINGS_INVALID);
        return (b[p] & 255L) | (b[p + 1] & 255L) << 8 | (b[p + 2] & 255L) << 16 | (b[p + 3] & 255L) << 24;
    }
}
