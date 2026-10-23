package org.openandroidauto.identity;

/** Public APK metadata pins and bounded read policy. Contains no projection credentials. */
public final class InstalledAaIdentityPolicy {
    private InstalledAaIdentityPolicy() {}
    public static final String PACKAGE = "com.google.android.projection.gearhead";
    public static final long VERSION_CODE = 178663874L;
    public static final String VERSION_NAME = "17.8.663874-release";
    public static final String APK_SHA256 = "4924df923f66b1574e2de65e2bc7f743d2b00a38954da78cc806bea10b0aa992";
    public static final String SIGNER_SHA256 = "1ca8dcc0bed3cbd872d2cb791200c0292ca9975768a82d676b8b424fb65b5295";
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    public static final long MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024;
    public static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    public static final int MAX_ENTRIES = 8192, MAX_DEX_ENTRIES = 16, MAX_SPLITS = 32;
    public static final long TIMEOUT_MS = 20_000;

    public static boolean versionMatches(long code, String name) {
        return code == VERSION_CODE && VERSION_NAME.equals(name);
    }
    public static boolean signerMatches(String[] currentSigners) {
        return currentSigners != null && currentSigners.length == 1 && SIGNER_SHA256.equals(currentSigners[0]);
    }
    public static boolean hashMatches(String hash) { return APK_SHA256.equals(hash); }
    public static boolean apkSizeAllowed(long size) { return size > 0 && size <= MAX_APK_BYTES; }
    public static boolean entryAllowed(int count, long entryBytes, long compressedBytes, long totalBefore) {
        return count > 0 && count <= MAX_ENTRIES && entryBytes >= 0 && entryBytes <= MAX_ENTRY_BYTES &&
                compressedBytes >= 0 && compressedBytes <= MAX_APK_BYTES && totalBefore >= 0 &&
                totalBefore <= MAX_UNCOMPRESSED_BYTES - entryBytes;
    }
    public static boolean entryNameAllowed(String name) {
        if (name == null || name.isEmpty() || name.length() > 512 || name.startsWith("/") ||
                name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String part : name.split("/", -1)) if (part.equals(".") || part.equals("..")) return false;
        return true;
    }
    public static boolean dexName(String name) {
        return name != null && name.matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    }
    public static boolean dexMagicAllowed(byte[] bytes) {
        if (bytes == null || bytes.length < 8 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x' ||
                bytes[3] != '\n' || bytes[7] != 0) return false;
        if (bytes[4] < '0' || bytes[4] > '9' || bytes[5] < '0' || bytes[5] > '9' || bytes[6] < '0' || bytes[6] > '9') return false;
        int version = (bytes[4] - '0') * 100 + (bytes[5] - '0') * 10 + bytes[6] - '0';
        return version >= 35 && version <= 41;
    }
}
