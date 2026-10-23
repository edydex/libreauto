package org.openandroidauto.offline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OfflineGraphDownloadPolicy {
    public static final long MAX_BYTES = 250_000_000L;
    private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private OfflineGraphDownloadPolicy() {}
    public static long totalBytes(int status, long existing, long contentLength, String contentRange) {
        if (status == 200) {
            if (contentLength < 1000 || contentLength > MAX_BYTES) throw new IllegalArgumentException("Road data size is unavailable or too large");
            return contentLength;
        }
        if (status != 206 || existing <= 0 || contentRange == null) throw new IllegalArgumentException("Road data download was not accepted");
        Matcher match = RANGE.matcher(contentRange);
        if (!match.matches()) throw new IllegalArgumentException("Road data resume response is invalid");
        long start, end, total;
        try { start = Long.parseLong(match.group(1)); end = Long.parseLong(match.group(2)); total = Long.parseLong(match.group(3)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Road data resume response is invalid"); }
        if (start != existing || end < start || end != total - 1 || total > MAX_BYTES || total < 1000
                || contentLength != total - existing) throw new IllegalArgumentException("Road data resume response is invalid");
        return total;
    }
}
