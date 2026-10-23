// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import android.content.Context;
import btools.router.FormatJson;
import btools.router.OsmNodeNamed;
import btools.router.OsmTrack;
import btools.router.ProfileCache;
import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.util.Crc32;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.openandroidauto.navigation.GeoPoint;
import org.openandroidauto.navigation.NavigationProviders;
import org.openandroidauto.navigation.RoutePlan;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Embedded car routing; reads downloaded graphs only and never opens a network connection.
 * Call on a worker. The download/store layer owns ready-area and complete-path coverage policy.
 * BRouter's 32MiB node-cache budget is not a promise about total Android process memory. */
public final class OfflineRoutingEngine {
    public static final long MAX_ROUTE_MILLIS = 12000;
    public static final int ROUTING_CACHE_MIB = 32;
    private static final int LOOKUP_VERSION = 11;
    private static final String PROFILE_REVISION = "brouter-1.7.10-car-fast";
    private static final Object ENGINE_LOCK = new Object();
    private final AtomicLong cancellation = new AtomicLong();
    private volatile RoutingEngine active;

    public OfflineRoutingEngine() {}

    public RoutePlan route(Context context, GeoPoint origin, NavigationProviders.Place destination,
                           Float bearing) throws IOException, InterruptedException {
        File base = new File(context.getFilesDir(), "offline-navigation");
        File profiles = new File(base, "profiles");
        prepareProfiles(context, profiles);
        return routeFiles(new File(base, "segments"), profiles, origin, destination, bearing);
    }

    /** Package-private files-only seam for real-graph JVM/Android acceptance probes. */
    RoutePlan routeFiles(File segments, File profiles, GeoPoint origin,
                        NavigationProviders.Place destination, Float bearing) throws IOException, InterruptedException {
        long token = cancellation.get();
        checkCancelled(token);
        synchronized (ENGINE_LOCK) {
            checkCancelled(token);
            for (GeoPoint endpoint : List.of(origin, destination.point)) {
                if (!new File(segments, tileName(endpoint.latitude, endpoint.longitude)).isFile())
                    throw new IOException("Download the route's offline area first");
            }
            RoutingContext config = new RoutingContext();
            config.localFunction = new File(profiles, "car-fast.brf").getAbsolutePath();
            config.memoryclass = ROUTING_CACHE_MIB;
            config.turnInstructionMode = 3;
            config.setAlternativeIdx(0);
            if (bearing != null && Float.isFinite(bearing) && bearing >= 0 && bearing < 360) {
                config.startDirection = Math.round(bearing) % 360;
                config.forceUseStartDirection = true;
            }
            RoutingEngine engine;
            try {
                engine = new RoutingEngine(null, null, segments,
                        Arrays.asList(waypoint(origin, "from"), waypoint(destination.point, "to")), config);
            } catch (RuntimeException invalidProfile) {
                throw new IOException("Offline routing files could not be opened");
            }
            active = engine;
            AtomicBoolean expired = new AtomicBoolean();
            Timer watchdog = new Timer("open-auto-offline-route-deadline", true);
            try {
                checkCancelled(token);
                engine.quite = true;
                watchdog.schedule(new TimerTask() {
                    @Override public void run() { expired.set(true); engine.terminate(); }
                }, MAX_ROUTE_MILLIS);
                engine.doRun(MAX_ROUTE_MILLIS);
                checkCancelled(token);
                if (expired.get()) throw new IOException("Offline route calculation timed out; choose a closer destination");
                OsmTrack track = engine.getFoundTrack();
                if (engine.getErrorMessage() != null || track == null || track.isDirty || track.nodes.size() < 2)
                    throw new IOException("No offline driving route was found in the downloaded data");
                track.name = "Offline route";
                RoutePlan result = parseGeoJson(new FormatJson(config).format(track), destination.name);
                checkCancelled(token);
                return result.withOffline();
            } catch (RuntimeException invalidRoute) {
                throw new IOException("Offline driving route could not be read");
            } finally {
                watchdog.cancel();
                if (!engine.isFinished()) ProfileCache.releaseProfile(config);
                if (active == engine) active = null;
            }
        }
    }

    public void cancel() {
        cancellation.incrementAndGet();
        RoutingEngine engine = active;
        if (engine != null) engine.terminate();
    }

    private void checkCancelled(long token) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || token != cancellation.get()) throw new InterruptedException();
    }

    private static OsmNodeNamed waypoint(GeoPoint point, String name) {
        OsmNodeNamed node = new OsmNodeNamed();
        node.ilon = (int) Math.round((point.longitude + 180) * 1000000);
        node.ilat = (int) Math.round((point.latitude + 90) * 1000000);
        node.name = name;
        return node;
    }

    /** Every 5x5-degree graph touched by the selected closed rectangle, not visible-map tiles. */
    public static List<String> requiredTileNames(OfflineAreaPolicy.Bounds bounds) {
        OfflineAreaPolicy.validate(bounds.north, bounds.east, bounds.south, bounds.west);
        TreeSet<String> names = new TreeSet<>();
        int west = (int) Math.floor(bounds.west / 5) * 5;
        int east = Math.min(175, (int) Math.floor(bounds.east / 5) * 5);
        int south = (int) Math.floor(bounds.south / 5) * 5;
        int north = (int) Math.floor(bounds.north / 5) * 5;
        for (int latitude = south; latitude <= north; latitude += 5)
            for (int longitude = west; longitude <= east; longitude += 5) names.add(tileName(latitude, longitude));
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    static String tileName(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude >= 90
                || longitude < -180 || longitude >= 180) throw new IllegalArgumentException("Unsupported offline coordinate");
        int west = (int) Math.floor(longitude / 5) * 5, south = (int) Math.floor(latitude / 5) * 5;
        return (west < 0 ? "W" + -west : "E" + west) + "_" + (south < 0 ? "S" + -south : "N" + south) + ".rd5";
    }

    /** Fast version/top-index CRC check. Downloads record a full SHA256 and file length;
     * ready checks use length, and BRouter validates individual blocks when routing reads them.
     * This method performs no full-file scan. */
    public static boolean validateGraphFile(File file) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[200]; input.readFully(header);
            input.seek(0);
            long last = 200;
            for (int index = 0; index < 25; index++) {
                long value = input.readLong();
                if (index == 0 && (value >>> 48) != LOOKUP_VERSION) return false;
                long end = value & 0xffffffffffffL;
                if (end < last || end > input.length()) return false;
                last = end;
            }
            if (input.length() < last + 112) return false;
            input.seek(last + 8);
            int stored = input.readInt(), actual = Crc32.crc(header, 0, header.length);
            return stored == actual || (stored ^ 2) == actual;
        } catch (IOException | RuntimeException corrupt) { return false; }
    }

    private static synchronized void prepareProfiles(Context context, File directory) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Offline profile storage is unavailable");
        File marker = new File(directory, PROFILE_REVISION);
        if (marker.isFile() && new File(directory, "car-fast.brf").isFile() && new File(directory, "lookups.dat").isFile()) return;
        for (String name : List.of("lookups.dat", "car-fast.brf")) {
            File staging = new File(directory, name + ".part");
            try (InputStream input = context.getAssets().open("offline-routing/" + name);
                 FileOutputStream output = new FileOutputStream(staging)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            } finally {
                if (Thread.currentThread().isInterrupted()) staging.delete();
            }
            Files.move(staging.toPath(), new File(directory, name).toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        }
        if (!marker.createNewFile() && !marker.isFile()) throw new IOException("Offline profile setup failed");
    }

    /** Pure parser for BRouter's GeoJSON; per-node model times retain modeled turn delays.
     * Road names/posted limits are not inferred from the kinematic speed model. */
    static RoutePlan parseGeoJson(String json, String destination) throws IOException {
        if (json.length() > 8_000_000) throw new IOException("Offline route is too large");
        try {
            JSONObject feature = new JSONObject(json).getJSONArray("features").getJSONObject(0);
            JSONObject geometry = feature.getJSONObject("geometry"), properties = feature.getJSONObject("properties");
            if (!"LineString".equals(geometry.getString("type"))) throw new IOException("Offline route has no driving line");
            JSONArray coordinates = geometry.getJSONArray("coordinates");
            if (coordinates.length() < 2 || coordinates.length() > 50000) throw new IOException("Offline route geometry is unsupported");
            List<GeoPoint> points = new ArrayList<>();
            for (int index = 0; index < coordinates.length(); index++) {
                JSONArray point = coordinates.getJSONArray(index);
                points.add(new GeoPoint(point.getDouble(1), point.getDouble(0)));
            }
            JSONArray rawTimes = properties.optJSONArray("times");
            double[] elapsed = new double[points.size()];
            if (rawTimes == null) {
                if (properties.getDouble("total-time") != 0) throw new IOException("Offline route timing is unavailable");
            } else {
                if (rawTimes.length() != points.size()) throw new IOException("Offline route timing differs from its geometry");
                for (int index = 0; index < elapsed.length; index++) {
                    Object raw = rawTimes.get(index);
                    if (!(raw instanceof Number)) throw new IOException("Offline route timing is invalid");
                    elapsed[index] = ((Number) raw).doubleValue();
                    if (!Double.isFinite(elapsed[index]) || elapsed[index] < 0 || elapsed[index] > 31 * 86400
                            || index > 0 && elapsed[index] < elapsed[index - 1]) throw new IOException("Offline route timing is invalid");
                }
            }
            double total = elapsed[elapsed.length - 1] - elapsed[0];
            TreeMap<Integer, Guide> guides = new TreeMap<>();
            guides.put(0, new Guide(1, "Continue on the road"));
            JSONArray hints = properties.optJSONArray("voicehints");
            int previous = -1;
            if (hints != null) for (int index = 0; index < hints.length(); index++) {
                JSONArray hint = hints.getJSONArray(index);
                int point = integer(hint.get(0)), command = integer(hint.get(1)), exit = integerSigned(hint.get(2));
                if (point <= previous || point < 0 || point >= points.size()) throw new IOException("Offline turn positions are invalid");
                if (command == 100 && point != points.size() - 1) throw new IOException("Offline arrival position is invalid");
                previous = point;
                guides.put(point, guide(command, exit));
            }
            guides.put(points.size() - 1, new Guide(4, "Arrive at " + destination));
            List<RoutePlan.Maneuver> maneuvers = new ArrayList<>();
            List<Map.Entry<Integer, Guide>> entries = new ArrayList<>(guides.entrySet());
            for (int index = 0; index < entries.size(); index++) {
                int begin = entries.get(index).getKey();
                int end = index + 1 < entries.size() ? entries.get(index + 1).getKey() : begin;
                Guide instruction = entries.get(index).getValue();
                maneuvers.add(new RoutePlan.Maneuver(begin, end, instruction.text, instruction.text, instruction.type,
                        elapsed[end] - elapsed[begin]));
            }
            double[] durations = new double[points.size() - 1], limits = new double[points.size() - 1];
            for (int index = 0; index < durations.length; index++) durations[index] = elapsed[index + 1] - elapsed[index];
            Arrays.fill(limits, Double.NaN);
            return new RoutePlan(points, maneuvers, total, destination).withRoadMetadata(durations, limits).withOffline();
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("Offline route output is invalid");
        }
    }

    private static int integer(Object raw) throws IOException {
        int result = integerSigned(raw);
        if (result < 0) throw new IOException("Offline turn value is invalid");
        return result;
    }
    private static int integerSigned(Object raw) throws IOException {
        if (!(raw instanceof Number)) throw new IOException("Offline turn value is invalid");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IOException("Offline turn value is invalid");
        return (int) value;
    }
    private static final class Guide {
        final int type; final String text;
        Guide(int type, String text) { this.type = type; this.text = text; }
    }
    private static Guide guide(int command, int exit) throws IOException {
        return switch (command) {
            case 1 -> new Guide(8, "Continue straight");
            case 2 -> new Guide(15, "Turn left");
            case 3 -> new Guide(16, "Bear left");
            case 4 -> new Guide(14, "Turn sharply left");
            case 5 -> new Guide(10, "Turn right");
            case 6 -> new Guide(9, "Bear right");
            case 7 -> new Guide(11, "Turn sharply right");
            case 8 -> new Guide(24, "Keep left");
            case 9 -> new Guide(23, "Keep right");
            case 10, 15 -> new Guide(13, "Make a U-turn");
            case 11 -> new Guide(12, "Make a right U-turn");
            case 13, 14 -> new Guide(26, exit == 0 ? "Enter the roundabout" : "At the roundabout, take exit " + Math.abs((long) exit));
            case 17 -> new Guide(19, "Take the exit on the left");
            case 18 -> new Guide(20, "Take the exit on the right");
            case 100 -> new Guide(4, "Arrive at your destination");
            default -> throw new IOException("Offline route contains an unsupported driving instruction");
        };
    }
}
