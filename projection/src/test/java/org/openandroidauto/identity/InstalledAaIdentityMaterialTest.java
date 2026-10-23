package org.openandroidauto.identity;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

/** Synthetic inputs only. No official APK, credential or encrypted Google payload is a fixture. */
public class InstalledAaIdentityMaterialTest {
    @Test public void base64SkipsAllNonalphabetBytesAndAcceptsValidUnpaddedTails() {
        assertArrayEquals(new byte[]{'M'}, AospSkipBase64.decode(new byte[]{'T',(byte)255,'Q','=','='}));
        assertArrayEquals(new byte[]{'M'}, AospSkipBase64.decode(ascii("T Q\n")));
        assertArrayEquals(ascii("Ma"), AospSkipBase64.decode(ascii("TW#E")));
        assertArrayEquals(ascii("Man"), AospSkipBase64.decode(ascii("TWFu")));
        assertArrayEquals(ascii("Ma"), AospSkipBase64.decode(ascii("TWE=\n")));
    }
    @Test public void base64RejectsIncompletePaddingAndNewDataAfterPadding() {
        for (String invalid : new String[]{"T", "TQ=", "=TQ==", "TQ===", "TQ==A", "TWE==", "TW=E"})
            assertThrows(IllegalArgumentException.class, () -> AospSkipBase64.decode(ascii(invalid)));
        assertThrows(IllegalArgumentException.class, () -> AospSkipBase64.decode(new byte[4097]));
    }
    @Test public void tweakUsesUnsignedDestinationAndReadsAliasedSourceDuringEachInnerStep() throws Exception {
        byte[] salt = new byte[256]; for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        byte[] buffer = new byte[48];
        InstalledAaIdentityMaterial.tweakBytes(ascii("synthetic seed only"), buffer, salt, () -> {});
        assertArrayEquals(hex("d2a0468636e0eef0deccf388dd2af24462de7f7f383bd44d92d042e5a06c492e9d0d7c31e8349be03bb4f67b7490df46"), buffer);
        byte[] detached = buffer.clone();
        InstalledAaIdentityMaterial.tweakBytes(buffer, buffer, salt, () -> {});
        assertArrayEquals(hex("a1558834c48fc10113827d4226e5f62e12d7b7bdf18c1caa642096a8f68b40e6136634f94cadec7a948679495ffe71ce"), buffer);
        byte[] separateOutput = detached.clone();
        InstalledAaIdentityMaterial.tweakBytes(detached, separateOutput, salt, () -> {});
        assertFalse(Arrays.equals(buffer, separateOutput));
    }
    @Test public void arrayPayloadRequiresAlignedByteElementHeaderExactCountAndBoundedBody() throws Exception {
        byte[] dex = dex(512); put16(dex, 128, 0x300); put16(dex, 130, 1); put32(dex, 132, 256);
        dex[136] = 42;
        byte[] result = InstalledAaIdentityMaterial.arrayData(dex, 128, 256);
        assertEquals(42, result[0]); result[0] = 0; assertEquals(42, dex[136]);
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.arrayData(dex, 130, 256));
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.arrayData(dex, 128, 255));
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.arrayData(dex, 128, 4097));
        put16(dex, 130, 2);
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.arrayData(dex, 128, 256));
    }
    @Test public void certificatePayloadMustBeReferencedByStringIdsAndUseExactAsciiPemBoundaries() throws Exception {
        byte[] pem = ascii("-----BEGIN CERTIFICATE-----\nQQ==\n-----END CERTIFICATE-----\n");
        byte[] dex = dex(512); put32(dex, 56, 1); put32(dex, 60, 112); put32(dex, 112, 128);
        dex[128] = (byte) pem.length; System.arraycopy(pem, 0, dex, 129, pem.length);
        assertArrayEquals(pem, InstalledAaIdentityMaterial.certificateString(dex, 128));
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.certificateString(dex, 129));
        put32(dex, 112, 127);
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.certificateString(dex, 128));
        put32(dex, 112, 128); dex[129 + pem.length] = 1;
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.certificateString(dex, 128));
        dex[129 + pem.length] = 0; dex[129] = (byte) 0x80;
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.certificateString(dex, 128));
    }
    @Test public void malformedDexTablesAndLengthCannotEscapeBoundedInput() throws Exception {
        byte[] dex = dex(512); InstalledAaIdentityMaterial.validateDexHeader(dex);
        put32(dex, 32, 513);
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.validateDexHeader(dex));
        put32(dex, 32, 512); put32(dex, 56, Integer.MAX_VALUE); put32(dex, 60, 112);
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.certificateString(dex, 128));
        put32(dex, 56, 1); put32(dex, 60, Integer.MAX_VALUE);
        assertThrows(Exception.class, () -> InstalledAaIdentityMaterial.certificateString(dex, 128));
    }
    @Test public void unrelatedZipIsFullyReadAndRefusedBeforeMaterialExists() throws Exception {
        byte[] bytes = archive("classes.dex", dex(512));
        byte[] withTail = Arrays.copyOf(bytes, bytes.length + 91);
        ByteArrayInputStream input = new ByteArrayInputStream(withTail);
        var refused = assertThrows(InstalledAaIdentityMaterial.Refused.class, () ->
            InstalledAaIdentityMaterial.prepare(input, withTail.length, () -> {}));
        assertEquals(InstalledAaIdentityMaterial.Status.APK_HASH_MISMATCH, refused.status);
        assertEquals(0, input.available());
    }
    @Test public void invalidSizeAndTraversalNamesFailWithoutReturningMaterial() throws Exception {
        var empty = assertThrows(InstalledAaIdentityMaterial.Refused.class, () ->
            InstalledAaIdentityMaterial.prepare(new ByteArrayInputStream(new byte[0]), 0, () -> {}));
        assertEquals(InstalledAaIdentityMaterial.Status.INPUT_BOUNDS, empty.status);
        byte[] escape = archive("../classes.dex", dex(512));
        var refused = assertThrows(InstalledAaIdentityMaterial.Refused.class, () ->
            InstalledAaIdentityMaterial.prepare(new ByteArrayInputStream(escape), escape.length, () -> {}));
        assertEquals(InstalledAaIdentityMaterial.Status.ZIP_INVALID, refused.status);
    }
    @Test public void readCancellationKeepsItsTypedReason() throws Exception {
        byte[] bytes = archive("classes.dex", dex(512)); AtomicInteger calls = new AtomicInteger();
        var abort = assertThrows(InstalledAaIdentityMaterial.Abort.class, () ->
            InstalledAaIdentityMaterial.prepare(new ByteArrayInputStream(bytes), bytes.length, () -> {
                if (calls.incrementAndGet() == 2) throw new InstalledAaIdentityMaterial.Abort(InstalledAaIdentityMaterial.AbortReason.CANCELLED);
            }));
        assertEquals(InstalledAaIdentityMaterial.AbortReason.CANCELLED, abort.reason);
    }
    @Test public void preparationStopsBeforeReadWhenAlreadyCancelled() {
        AtomicInteger reads = new AtomicInteger();
        java.io.InputStream source = new java.io.InputStream() { public int read() { reads.incrementAndGet(); return -1; } };
        assertThrows(InstalledAaIdentityMaterial.Abort.class, () ->
            InstalledAaIdentityMaterial.prepare(source, 10, () -> {
                throw new InstalledAaIdentityMaterial.Abort(InstalledAaIdentityMaterial.AbortReason.CANCELLED);
            }));
        assertEquals(0, reads.get());
    }
    @Test public void timeoutInAliasedTweakIsCooperativeAndTyped() {
        var abort = assertThrows(InstalledAaIdentityMaterial.Abort.class, () ->
            InstalledAaIdentityMaterial.tweakBytes(new byte[48], new byte[48], new byte[256], () -> {
                throw new InstalledAaIdentityMaterial.Abort(InstalledAaIdentityMaterial.AbortReason.TIMED_OUT);
            }));
        assertEquals(InstalledAaIdentityMaterial.AbortReason.TIMED_OUT, abort.reason);
    }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static byte[] dex(int length) {
        byte[] out = new byte[length]; byte[] magic = new byte[]{'d','e','x','\n','0','3','9',0};
        System.arraycopy(magic, 0, out, 0, magic.length); put32(out, 32, length); put32(out, 36, 112); put32(out, 40, 0x12345678);
        return out;
    }
    private static void put16(byte[] out, int offset, int n) { out[offset] = (byte) n; out[offset + 1] = (byte) (n >> 8); }
    private static void put32(byte[] out, int offset, int n) { for (int i = 0; i < 4; i++) out[offset + i] = (byte) (n >>> (8 * i)); }
    private static byte[] archive(String name, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(payload); zip.closeEntry();
        }
        return out.toByteArray();
    }
}
