package org.openandroidauto.offline;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openandroidauto.navigation.GeoPoint;
import org.openandroidauto.offline.address.OfflineAddressIndex;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Small transactional readiness records. Graphs are shared; address areas own their index membership. */
public final class OfflineNavigationStore {
    private OfflineNavigationStore() {}
    public static File directory(Context context) { return new File(context.getFilesDir(), "offline-navigation"); }
    public static File segments(Context context) { return new File(directory(context), "segments"); }
    private static AtomicFile manifest(Context context) { return new AtomicFile(new File(directory(context), "areas.json")); }
    private static JSONArray records(Context context) {
        try {
            AtomicFile file = manifest(context);
            if (!file.getBaseFile().exists() || file.getBaseFile().length() > 1000000) return new JSONArray();
            try (java.io.InputStream input = file.openRead()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 1000000) return new JSONArray();
                    bytes.write(buffer, 0, count);
                }
                JSONObject document = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if (document.getInt("version") != 1) return new JSONArray();
                JSONArray areas = document.getJSONArray("areas");
                return areas.length() <= OfflineAreaPolicy.MAX_AREAS ? areas : new JSONArray();
            }
        } catch (Exception invalid) { return new JSONArray(); }
    }
    private static void write(Context context, JSONArray areas) throws IOException {
        directory(context).mkdirs(); AtomicFile file = manifest(context); FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(new JSONObject().put("version", 1).put("areas", areas).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception failure) { if (output != null) file.failWrite(output); throw new IOException("Navigation download could not be saved"); }
    }
    public static synchronized boolean isReady(Context context, String id) {
        JSONArray all = records(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject area = all.optJSONObject(i);
            if (area != null && id.equals(area.optString("id")) && ready(context, area)) return true;
        }
        return false;
    }
    private static boolean ready(Context context, JSONObject record) {
        return ready(context, record, null);
    }
    private static boolean ready(Context context, JSONObject record, Set<String> indexed) {
        try {
            String id = record.getString("id"); UUID.fromString(id);
            if (!record.optBoolean("complete")) return false;
            bounds(record); JSONArray tiles = record.getJSONArray("tiles");
            if (tiles.length() == 0 || tiles.length() > 4 || (indexed == null
                    ? OfflineAddressIndex.open(context).status(id) == null : !indexed.contains(id))) return false;
            for (int i = 0; i < tiles.length(); i++) {
                JSONObject tile = tiles.getJSONObject(i); String name = tile.getString("name");
                if (!validTileName(name)) return false;
                File path = new File(segments(context), name);
                if (!path.isFile() || path.length() != tile.getLong("bytes") || path.length() < 1000) return false;
            }
            return true;
        } catch (Exception invalid) { return false; }
    }
    public static boolean validTileName(String name) { return name != null && name.matches("[EW]\\d{1,3}_[NS]\\d{1,2}\\.rd5"); }
    public static synchronized String prepare(Context context, String id, OfflineAreaPolicy.Bounds bounds, List<String> tileNames) throws IOException {
        UUID.fromString(id);
        try {
            JSONArray tiles = new JSONArray();
            for (String name : tileNames) {
                if (!validTileName(name)) throw new IOException("Invalid road data name");
                tiles.put(new JSONObject().put("name", name).put("bytes", 0));
            }
            String revision = UUID.randomUUID().toString();
            JSONObject entry = new JSONObject().put("id", id).put("north", bounds.north).put("east", bounds.east)
                    .put("south", bounds.south).put("west", bounds.west).put("tiles", tiles).put("complete", false).put("revision", revision);
            JSONArray previous = records(context), next = new JSONArray();
            for (int i = 0; i < previous.length(); i++) if (!id.equals(previous.getJSONObject(i).optString("id"))) next.put(previous.getJSONObject(i));
            if (next.length() >= OfflineAreaPolicy.MAX_AREAS) throw new IOException("Too many downloaded areas");
            next.put(entry); write(context, next); return revision;
        } catch (org.json.JSONException invalid) { throw new IOException("Navigation download could not be saved"); }
    }
    public static synchronized void commit(Context context, String id, OfflineAreaPolicy.Bounds bounds, JSONArray tiles, String revision) throws IOException {
        UUID.fromString(id);
        if (OfflineAddressIndex.open(context).status(id) == null) throw new IOException("Addresses have not finished downloading");
        try {
            JSONArray previous = records(context), next = new JSONArray(); boolean current = false;
            for (int i = 0; i < previous.length(); i++) {
                JSONObject prior = previous.getJSONObject(i);
                if (id.equals(prior.optString("id"))) current = revision.equals(prior.optString("revision"));
                else next.put(prior);
            }
            if (!current) throw new IOException("This area download was cancelled");
            JSONObject entry = new JSONObject().put("id", id).put("north", bounds.north).put("east", bounds.east)
                    .put("south", bounds.south).put("west", bounds.west).put("tiles", tiles).put("complete", true).put("completed", System.currentTimeMillis());
            if (!ready(context, entry)) throw new IOException("Road data is incomplete");
            if (next.length() >= OfflineAreaPolicy.MAX_AREAS) throw new IOException("Too many downloaded areas");
            next.put(entry); write(context, next);
        } catch (org.json.JSONException invalid) { throw new IOException("Navigation download could not be saved"); }
    }
    public static synchronized void remove(Context context, String id) throws IOException {
        JSONArray previous = records(context), next = new JSONArray();
        Set<String> retained = new HashSet<>(), removed = new HashSet<>();
        for (int i = 0; i < previous.length(); i++) {
            JSONObject area = previous.optJSONObject(i); if (area == null) continue;
            boolean deleting = id.equals(area.optString("id")); if (!deleting) next.put(area);
            JSONArray tiles = area.optJSONArray("tiles");
            if (tiles != null) for (int j = 0; j < tiles.length(); j++) {
                JSONObject tile = tiles.optJSONObject(j); String name = tile == null ? "" : tile.optString("name");
                if (validTileName(name)) (deleting ? removed : retained).add(name);
            }
        }
        write(context, next); // Invalidates readiness before removing index or graph files.
        OfflineAddressIndex.open(context).removeArea(id);
        for (String name : removed) if (!retained.contains(name)) {
            Files.deleteIfExists(new File(segments(context), name).toPath());
            Files.deleteIfExists(new File(segments(context), name + ".part").toPath());
            Files.deleteIfExists(new File(segments(context), name + ".part.json").toPath());
        }
    }
    private static OfflineAreaPolicy.Bounds bounds(JSONObject area) throws org.json.JSONException {
        return OfflineAreaPolicy.validate(area.getDouble("north"), area.getDouble("east"), area.getDouble("south"), area.getDouble("west"));
    }
    private static List<OfflineAreaPolicy.Bounds> readyBounds(Context context) {
        List<OfflineAreaPolicy.Bounds> result = new ArrayList<>(); JSONArray all = records(context);
        for (int i = 0; i < all.length(); i++) try {
            JSONObject record = all.getJSONObject(i); if (ready(context, record)) result.add(bounds(record));
        } catch (Exception invalid) { /* Never infer readiness from damaged or incomplete records. */ }
        return result;
    }
    /** Worker-only batched snapshot for the phone picker, never per-area SQLite work on Main. */
    public static synchronized Set<String> readyIds(Context context) {
        Set<String> indexed = new HashSet<>(), result = new HashSet<>();
        try { for (OfflineAddressIndex.AreaStatus area : OfflineAddressIndex.open(context).areas()) indexed.add(area.areaId); }
        catch (IOException unavailable) { return result; }
        JSONArray all = records(context);
        for (int i = 0; i < all.length(); i++) {
            JSONObject area = all.optJSONObject(i);
            if (area != null && ready(context, area, indexed)) result.add(area.optString("id"));
        }
        return result;
    }
    public static synchronized boolean available(Context context, GeoPoint origin, GeoPoint destination) {
        List<OfflineAreaPolicy.Bounds> areas = readyBounds(context);
        return OfflineNavigationCoverage.contains(areas, origin) && OfflineNavigationCoverage.contains(areas, destination);
    }
    public static synchronized boolean coversRoute(Context context, List<GeoPoint> points) {
        return OfflineNavigationCoverage.covers(readyBounds(context), points);
    }
}
