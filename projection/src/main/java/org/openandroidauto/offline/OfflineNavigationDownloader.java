package org.openandroidauto.offline;

import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openandroidauto.navigation.NavigationConfig;
import org.openandroidauto.offline.address.OfflineAddressDownloader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;

/** Foreground, explicit-area downloads. No service, periodic work, or location-dependent network calls. */
public final class OfflineNavigationDownloader {
    public interface Progress { void onProgress(String stage, long downloaded, long total); }
    private static final String GRAPH_BASE = "https://brouter.de/brouter/segments4/";
    private static final java.util.concurrent.Semaphore PIPELINE = new java.util.concurrent.Semaphore(1);
    private OfflineNavigationDownloader() {}
    public static void removeArea(Context context, String id) throws IOException {
        if (!PIPELINE.tryAcquire()) throw new IOException("The previous download is stopping. Try deleting again");
        try { OfflineNavigationStore.remove(context, id); }
        finally { PIPELINE.release(); }
    }
    public static void download(Context context, String id, OfflineAreaPolicy.Bounds bounds,
                                CancellationSignal cancellation, Progress progress) throws IOException {
        if (!PIPELINE.tryAcquire()) throw new IOException("The previous download is stopping. Try Resume again");
        try { downloadOwned(context, id, bounds, cancellation, progress); }
        finally { PIPELINE.release(); }
    }
    private static void downloadOwned(Context context, String id, OfflineAreaPolicy.Bounds bounds,
                                      CancellationSignal cancellation, Progress progress) throws IOException {
        cancellation.throwIfCanceled();
        OfflineAreaPolicy.requireDownloadable(bounds);
        List<String> names = OfflineRoutingEngine.requiredTileNames(bounds);
        if (names.isEmpty() || names.size() > 4) throw new IOException("Choose a smaller navigation area");
        String revision = OfflineNavigationStore.prepare(context, id, bounds, names);
        File directory = OfflineNavigationStore.segments(context);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Road data storage is unavailable");
        JSONArray tiles = new JSONArray();
        try {
            for (int i = 0; i < names.size(); i++) {
                cancellation.throwIfCanceled(); String name = names.get(i);
                String stage = "Road data " + (i + 1) + " of " + names.size() + " · shared with nearby areas";
                File file = graph(directory, name, cancellation, (done, total) -> progress.onProgress(stage, done, total));
                tiles.put(new JSONObject().put("name", name).put("bytes", file.length()).put("sha256", hash(file, cancellation)));
            }
            cancellation.throwIfCanceled();
            OfflineAddressDownloader.fetchAndReplace(context, id, bounds, cancellation,
                    (bytes, entries) -> progress.onProgress("Addresses and places · " + entries + " indexed", bytes, -1));
            cancellation.throwIfCanceled();
            OfflineNavigationStore.commit(context, id, bounds, tiles, revision);
        } catch (OperationCanceledException cancelled) { throw cancelled; }
        catch (org.json.JSONException invalid) { throw new IOException("Navigation data could not be saved"); }
    }
    private interface ByteProgress { void update(long done, long total); }
    private static File graph(File directory, String name, CancellationSignal cancellation, ByteProgress progress) throws IOException {
        if (!OfflineNavigationStore.validTileName(name)) throw new IOException("Invalid road data name");
        File target = new File(directory, name);
        if (target.isFile() && target.length() >= 1000 && target.length() <= OfflineGraphDownloadPolicy.MAX_BYTES) {
            progress.update(target.length(), target.length());
            if (OfflineRoutingEngine.validateGraphFile(target)) return target;
            Files.delete(target.toPath());
        }
        cancellation.throwIfCanceled();
        File partial = new File(directory, name + ".part"), metadata = new File(directory, name + ".part.json");
        String validator = "";
        try { if (metadata.length() < 4096) validator = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8)).optString("validator"); }
        catch (Exception ignored) { /* A partial without a matching validator is restarted. */ }
        long existing = partial.length();
        if (existing > OfflineGraphDownloadPolicy.MAX_BYTES || validator.isBlank()) { Files.deleteIfExists(partial.toPath()); existing = 0; }
        HttpURLConnection connection = (HttpURLConnection) URI.create(GRAPH_BASE + name).toURL().openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", NavigationConfig.USER_AGENT);
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0) { connection.setRequestProperty("Range", "bytes=" + existing + "-"); connection.setRequestProperty("If-Range", validator); }
        cancellation.setOnCancelListener(() -> {
            Thread closer = new Thread(connection::disconnect, "open-auto-road-cancel"); closer.setDaemon(true); closer.start();
        });
        try {
            int code = connection.getResponseCode();
            long total;
            try { total = OfflineGraphDownloadPolicy.totalBytes(code, existing, connection.getContentLengthLong(), connection.getHeaderField("Content-Range")); }
            catch (IllegalArgumentException invalid) { throw new IOException(invalid.getMessage()); }
            if (code == 200) existing = 0;
            if (directory.getUsableSpace() < total - existing + 25_000_000L) throw new IOException("Not enough free storage for this road region");
            String responseValidator = connection.getHeaderField("ETag");
            if (responseValidator == null || responseValidator.isBlank()) responseValidator = connection.getHeaderField("Last-Modified");
            if (responseValidator == null) responseValidator = "";
            if (code == 206 && !responseValidator.isBlank() && !responseValidator.equals(validator))
                throw new IOException("Road data changed. Retry the download");
            try { Files.write(metadata.toPath(), new JSONObject().put("validator", responseValidator).toString().getBytes(StandardCharsets.UTF_8)); }
            catch (org.json.JSONException invalid) { throw new IOException("Road download could not be saved"); }
            long done = existing;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial, existing > 0)) {
                byte[] buffer = new byte[65536]; int count;
                long lastProgress = 0;
                while ((count = input.read(buffer)) != -1) {
                    cancellation.throwIfCanceled();
                    if (done + count > total) throw new IOException("Road data response exceeded its size");
                    output.write(buffer, 0, count); done += count;
                    if (System.nanoTime() - lastProgress > 200_000_000L) { progress.update(done, total); lastProgress = System.nanoTime(); }
                }
                output.getFD().sync();
            }
            cancellation.throwIfCanceled();
            if (done != total) throw new IOException("Road data download was interrupted. Resume to finish");
            if (!OfflineRoutingEngine.validateGraphFile(partial)) {
                Files.deleteIfExists(partial.toPath()); Files.deleteIfExists(metadata.toPath());
                throw new IOException("Road data verification failed. Retry the download");
            }
            cancellation.throwIfCanceled();
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(metadata.toPath()); progress.update(done, total); return target;
        } finally { cancellation.setOnCancelListener(null); connection.disconnect(); }
    }
    private static String hash(File file, CancellationSignal cancellation) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; int count;
            try (FileInputStream input = new FileInputStream(file)) {
                while ((count = input.read(buffer)) != -1) { cancellation.throwIfCanceled(); digest.update(buffer, 0, count); }
            }
            StringBuilder text = new StringBuilder(64); for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("Road data verification is unavailable"); }
    }
}
