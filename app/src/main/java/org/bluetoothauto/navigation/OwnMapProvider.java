// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small online Photon/Valhalla adapters. Requests run on a worker, never for each GPS fix. */
public final class OwnMapProvider {
    public static final String STYLE = "https://tiles.openfreemap.org/styles/liberty";
    private static final String SEARCH = "https://photon.komoot.io/api/";
    private static final String ROUTE = "https://valhalla1.openstreetmap.de/route";
    private static final String ROAD_METADATA = "https://valhalla1.openstreetmap.de/trace_attributes";
    private static final Object REQUEST_GATE = new Object();
    private static long lastRequestNanos;
    public static final class Place {
        public final String name;
        public final GeoPoint point;
        public final List<String> roadNames;
        public Place(String name, GeoPoint point) {
            this(name, point, java.util.Collections.emptyList());
        }
        public Place(String name, GeoPoint point, List<String> roadNames) {
            if (name == null || name.trim().isEmpty() || point == null) throw new IllegalArgumentException("Choose a destination");
            this.name = name.trim().substring(0, Math.min(240, name.trim().length()));
            this.point = point;
            if (roadNames == null || roadNames.size() > 16) throw new IllegalArgumentException("Invalid road labels");
            List<String> labels = new ArrayList<>();
            for (String label : roadNames) if (label != null && !label.trim().isEmpty() && label.length() <= 240) labels.add(label.trim());
            this.roadNames = java.util.Collections.unmodifiableList(labels);
        }
    }
    private volatile HttpURLConnection connection;

    public List<Place> search(String query, GeoPoint bias) throws Exception {
        return search(query, bias, 0);
    }
    public List<Place> search(String query, GeoPoint bias, long maximumMillis) throws Exception {
        if (query == null || query.trim().length() < 2 || query.length() > 300)
            throw new IllegalArgumentException("Enter a place or address");
        String url = SEARCH + "?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&limit=8";
        if (bias != null) url += "&lat=" + bias.latitude + "&lon=" + bias.longitude;
        return parseSearch(request(url, null, maximumMillis));
    }

    public RoutePlan route(GeoPoint origin, Place destination, Float bearing) throws Exception {
        return parseRoute(request(ROUTE, routeRequest(origin, destination, bearing, false)), destination.name);
    }

    /** Candidates are only geometry proposals; GoogleRoutePlanner must validate Google's turn list. */
    public List<RoutePlan> routeCandidates(GeoPoint origin, Place destination, Float bearing) throws Exception {
        return routeCandidates(origin, destination, bearing, 22_000);
    }
    public List<RoutePlan> routeCandidates(GeoPoint origin, Place destination, Float bearing, long maximumMillis) throws Exception {
        return parseRouteCandidates(request(ROUTE, routeRequest(origin, destination, bearing, true), maximumMillis), destination.name);
    }

    static String routeRequest(GeoPoint origin, Place destination, Float bearing, boolean candidates) throws JSONException {
        JSONObject start = new JSONObject().put("lat", origin.latitude).put("lon", origin.longitude).put("type", "break");
        if (bearing != null && Float.isFinite(bearing) && bearing >= 0 && bearing < 360) {
            if (candidates) {
                // A near opposite carriageway must not outrank the actual
                // course. Validation still independently caps origin error25m.
                start.put("heading", Math.round(bearing) % 360).put("heading_tolerance", 40)
                        .put("radius", 25).put("rank_candidates", false);
            } else start.put("heading", bearing);
        }
        JSONObject end = new JSONObject().put("lat", destination.point.latitude).put("lon", destination.point.longitude).put("type", "break");
        JSONObject body = new JSONObject().put("locations", new JSONArray().put(start).put(end))
                .put("costing", "auto").put("units", "kilometers").put("language", "en-US")
                .put("directions_type", "instructions").put("shape_format", "polyline6");
        if (candidates) body.put("alternates", 2);
        return body.toString();
    }

    /** Optional once-per-route pass, after displaying the basic route. Never called for GPS fixes.
     * Unsupported/missing limits and any changed geometry leave the original route untouched. */
    public RoutePlan enrichRoute(RoutePlan route) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (route.metadataEnriched || route.offline || route.points.size() > 10_000) return route;
        try {
            String body = new JSONObject().put("encoded_polyline", RoutePlan.encodePolyline6(route.points))
                    .put("shape_match", "walk_or_snap").put("shape_format", "polyline6")
                    .put("costing", "auto").put("units", "kilometers")
                    .put("filters", new JSONObject().put("action", "include").put("attributes", new JSONArray()
                            .put("shape").put("shape_attributes.speed_limit")
                            .put("edge.names").put("edge.begin_shape_index").put("edge.end_shape_index")
                            .put("edge.travel_mode").put("edge.use"))).toString();
            return parseRoadSpeedLimits(request(ROAD_METADATA, body, 12_000), route);
        } catch (InterruptedException cancelled) { throw cancelled; }
        catch (Exception unavailable) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            return route;
        }
    }

    /** Valhalla trace serializer emits shape speed_limit in km/h even when units=miles.
     * https://github.com/valhalla/valhalla/blob/master/src/tyr/trace_serializer.cc
     * Require the identical route shape: map-matching output is not permission to reassign roads. */
    public static RoutePlan parseRoadSpeedLimits(String json, RoutePlan route) throws JSONException {
        JSONObject root = new JSONObject(json);
        List<GeoPoint> points = RoutePlan.decodePolyline6(root.optString("shape"));
        if (points.size() != route.points.size()) throw new IllegalArgumentException("Speed-limit route shape differs");
        for (int index = 0; index < points.size(); index++) {
            GeoPoint expected = route.points.get(index), observed = points.get(index);
            if (expected.latitude != observed.latitude || expected.longitude != observed.longitude)
                throw new IllegalArgumentException("Speed-limit route shape differs");
        }
        JSONObject attributes = root.optJSONObject("shape_attributes");
        JSONArray raw = attributes == null ? null : attributes.optJSONArray("speed_limit");
        RoutePlan result = route;
        if (raw != null && raw.length() != points.size() - 1) throw new IllegalArgumentException("Speed-limit segment count differs");
        if (raw != null) {
            double[] limits = new double[raw.length()];
            boolean known = false;
            for (int index = 0; index < limits.length; index++) {
                Object item = raw.opt(index);
                double value = item instanceof Number ? ((Number) item).doubleValue() : Double.NaN;
                if (value == 255) value = Double.POSITIVE_INFINITY;
                else if (!Double.isFinite(value) || value <= 0 || value >= 255) value = Double.NaN;
                limits[index] = value;
                known |= !Double.isNaN(value);
            }
            if (known) result = route.withRoadMetadata(null, limits);
        }
        JSONArray edges = root.optJSONArray("edges");
        if (edges != null && edges.length() > 0) {
            if (edges.length() > 20_000) throw new IllegalArgumentException("Too many route edges");
            List<RoutePlan.RoadSpan> spans = new ArrayList<>();
            int next = 0;
            for (int index = 0; index < edges.length(); index++) {
                JSONObject edge = edges.getJSONObject(index);
                int begin = shapeIndex(edge, "begin_shape_index"), end = shapeIndex(edge, "end_shape_index");
                if (begin != next || end < begin || end >= points.size()) throw new IllegalArgumentException("Road edge geometry differs");
                if (end == begin) continue; // A zero-length internal connector labels no shape segment.
                JSONArray names = edge.optJSONArray("names");
                if (edge.has("names") && names == null || names != null && names.length() > 64)
                    throw new IllegalArgumentException("Invalid route edge names");
                List<String> labels = new ArrayList<>();
                if (names != null) for (int n = 0; n < names.length(); n++) {
                    Object name = names.opt(n);
                    if (!(name instanceof String) || ((String) name).length() > 240)
                        throw new IllegalArgumentException("Invalid route edge name");
                    if (!((String) name).trim().isEmpty()) labels.add(((String) name).trim());
                }
                String mode = edge.optString("travel_mode", ""), use = edge.optString("use", "");
                spans.add(new RoutePlan.RoadSpan(begin, end, labels, mode, use));
                next = end;
            }
            if (next != points.size() - 1) throw new IllegalArgumentException("Road edges do not cover route");
            result = result.withRoadSpans(spans);
        }
        return result;
    }

    private static int shapeIndex(JSONObject object, String key) throws JSONException {
        Object raw = object.get(key);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("Invalid road edge index");
        double number = ((Number) raw).doubleValue();
        int value = ((Number) raw).intValue();
        if (!Double.isFinite(number) || number != value) throw new IllegalArgumentException("Invalid road edge index");
        return value;
    }

    /** Caller interrupts its worker too; socket cleanup never stalls a phone tap. */
    public void cancel() {
        HttpURLConnection active = connection;
        if (active != null) {
            Thread cleanup = new Thread(() -> { try { active.disconnect(); } catch (RuntimeException ignored) {} }, "own-map-cancel");
            cleanup.setDaemon(true); cleanup.start();
        }
    }

    private String request(String url, String body) throws Exception {
        return request(url, body, 0);
    }

    private String request(String url, String body, long maximumMillis) throws Exception {
        final long deadline = maximumMillis > 0 ? System.nanoTime() + maximumMillis * 1_000_000L : 0;
        // Search and routing share a modest ceiling across instances. Cancellation reserves no slot.
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            remainingTimeout(deadline, 12_000);
            long wait;
            synchronized (REQUEST_GATE) {
                long now = System.nanoTime();
                wait = 1_100_000_000L - (now - lastRequestNanos);
                if (wait <= 0) { lastRequestNanos = now; break; }
            }
            Thread.sleep((wait + 999_999) / 1_000_000);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection active = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection = active;
        try {
            active.setConnectTimeout(remainingTimeout(deadline, 8000));
            active.setReadTimeout(remainingTimeout(deadline, 12000));
            active.setInstanceFollowRedirects(false);
            active.setRequestProperty("User-Agent", "BluetoothAuto/0.1 (personal navigation prototype)");
            active.setRequestProperty("X-Client-Id", "bluetooth-auto-research");
            active.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                active.setRequestMethod("POST"); active.setDoOutput(true);
                active.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                active.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = active.getOutputStream()) { out.write(bytes); }
            }
            active.setReadTimeout(remainingTimeout(deadline, 12000));
            int status = active.getResponseCode();
            if (status == 429) throw new IOException("Navigation server is busy. Try again shortly.");
            if (status != 200) throw new IOException("Navigation server is unavailable. Try again later.");
            try (InputStream input = active.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while (true) {
                    active.setReadTimeout(remainingTimeout(deadline, 12000));
                    count = input.read(buffer);
                    if (count < 0) break;
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + count > 6_000_000) throw new IOException("This route is too large. Choose a closer destination.");
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        } finally { active.disconnect(); if (connection == active) connection = null; }
    }

    private static int remainingTimeout(long deadline, int usualMillis) throws SocketTimeoutException {
        if (deadline == 0) return usualMillis;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("Optional road metadata deadline expired");
        return (int) Math.max(1, Math.min(usualMillis, (remaining + 999_999) / 1_000_000));
    }

    public static List<Place> parseSearch(String json) throws JSONException {
        JSONArray features = new JSONObject(json).getJSONArray("features");
        List<Place> places = new ArrayList<>();
        for (int index = 0; index < Math.min(8, features.length()); index++) {
            try {
                JSONObject feature = features.getJSONObject(index), geo = feature.getJSONObject("geometry");
                if (!"Point".equals(geo.optString("type"))) continue;
                JSONArray coords = geo.getJSONArray("coordinates");
                JSONObject props = feature.getJSONObject("properties");
                List<String> roadNames = new ArrayList<>();
                if ("highway".equals(props.optString("osm_key")) && !props.optString("name").trim().isEmpty()) roadNames.add(props.optString("name"));
                if (!props.optString("street").trim().isEmpty()) roadNames.add(props.optString("street"));
                String name = props.optString("name", "");
                String street = (props.optString("housenumber", "") + " " + props.optString("street", "")).trim();
                if (name.trim().isEmpty()) name = street;
                else if (!street.trim().isEmpty() && !street.equals(name)) name += ", " + street;
                String city = props.optString("city", props.optString("county", ""));
                if (!city.trim().isEmpty() && !city.equals(name)) name += ", " + city;
                if (!props.optString("state", "").trim().isEmpty()) name += ", " + props.optString("state");
                if (!name.trim().isEmpty()) places.add(new Place(name, new GeoPoint(coords.getDouble(1), coords.getDouble(0)), roadNames));
            } catch (IllegalArgumentException | JSONException ignored) { /* A bad result cannot hide the others. */ }
        }
        return places;
    }

    public static RoutePlan parseRoute(String json, String destinationName) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.has("error")) throw new IllegalArgumentException("No driving route was found");
        JSONObject trip = root.getJSONObject("trip");
        if (trip.optInt("status", 0) != 0) throw new IllegalArgumentException("No driving route was found");
        JSONArray legs = trip.getJSONArray("legs");
        if (legs.length() != 1) throw new IllegalArgumentException("Unsupported route response");
        JSONObject leg = legs.getJSONObject(0);
        List<GeoPoint> points = RoutePlan.decodePolyline6(leg.getString("shape"));
        List<RoutePlan.Maneuver> maneuvers = new ArrayList<>();
        JSONArray steps = leg.getJSONArray("maneuvers");
        if (steps.length() > 4000) throw new IllegalArgumentException("Too many route instructions");
        for (int index = 0; index < steps.length(); index++) {
            JSONObject item = steps.getJSONObject(index);
            String instruction = item.getString("instruction").trim();
            if (instruction.length() > 400) instruction = instruction.substring(0, 400);
            Object time = item.opt("time");
            double seconds = time instanceof Number ? ((Number) time).doubleValue() : Double.NaN;
            if (!Double.isFinite(seconds) || seconds < 0) seconds = Double.NaN;
            maneuvers.add(new RoutePlan.Maneuver(item.getInt("begin_shape_index"), item.getInt("end_shape_index"),
                    instruction, instruction, item.optInt("type", 0), seconds,
                    labelArray(item.optJSONArray("street_names")), labelArray(item.optJSONArray("begin_street_names")), signLabels(item.optJSONObject("sign"))));
        }
        return new RoutePlan(points, maneuvers, trip.getJSONObject("summary").getDouble("time"), destinationName);
    }

    public static List<RoutePlan> parseRouteCandidates(String json, String destinationName) throws JSONException {
        List<RoutePlan> result = new ArrayList<>();
        result.add(parseRoute(json, destinationName));
        JSONArray alternates = new JSONObject(json).optJSONArray("alternates");
        if (alternates != null) for (int i = 0; i < Math.min(2, alternates.length()); i++) {
            JSONObject item = alternates.optJSONObject(i);
            if (item == null) continue;
            try { result.add(parseRoute(item.toString(), destinationName)); }
            catch (JSONException | IllegalArgumentException invalid) { /* An invalid alternative is not route evidence. */ }
        }
        return java.util.Collections.unmodifiableList(result);
    }

    private static List<String> labelArray(JSONArray raw) {
        List<String> values = new ArrayList<>();
        if (raw != null) for (int i = 0; i < Math.min(32, raw.length()); i++) {
            Object value = raw.opt(i);
            if (value instanceof String && !((String) value).trim().isEmpty())
                values.add(((String) value).trim().substring(0, Math.min(240, ((String) value).trim().length())));
        }
        return values;
    }

    private static List<String> signLabels(JSONObject sign) {
        List<String> values = new ArrayList<>();
        if (sign == null) return values;
        for (String key : List.of("exit_number_elements", "exit_branch_elements", "exit_toward_elements", "exit_name_elements")) {
            JSONArray raw = sign.optJSONArray(key);
            if (raw != null) for (int i = 0; i < Math.min(16, raw.length()) && values.size() < 64; i++) {
                JSONObject item = raw.optJSONObject(i);
                Object text = item == null ? null : item.opt("text");
                if (text instanceof String && !((String) text).trim().isEmpty())
                    values.add(((String) text).trim().substring(0, Math.min(240, ((String) text).trim().length())));
            }
        }
        return values;
    }
}
