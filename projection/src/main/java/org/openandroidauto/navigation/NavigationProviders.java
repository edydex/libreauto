package org.openandroidauto.navigation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded direct adapters for interchangeable open-source route and search servers. */
public final class NavigationProviders {
    private static final ExecutorService CONNECTION_CLEANUP = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "open-auto-provider-cleanup"); thread.setDaemon(true); return thread;
    });
    public static final class Place {
        public final String name;
        public final GeoPoint point;
        public Place(String name, GeoPoint point) { this.name = name; this.point = point; }
    }

    private final NavigationConfig config;
    private long lastRequestNanos;
    private volatile HttpURLConnection connection;
    private HttpURLConnection cancellationQueued;
    private enum MetadataOutcome {
        NOT_REQUESTED, RUNNING, SUCCESS, UNCHANGED, SKIPPED_ENDPOINT, SKIPPED_ALREADY_ENRICHED,
        SKIPPED_POINT_LIMIT, HTTP_FAILURE, TIMEOUT, IO_FAILURE, JSON_FAILURE, INVALID_METADATA, CANCELLED
    }
    private enum MetadataValidation {
        NOT_CHECKED, VALID, GEOMETRY, AMBIGUOUS_GEOMETRY, LIMIT_ARRAY, TIME_ARRAY, EDGES,
        UNITS, EDGE_ORDER, EDGE_COVERAGE, INDEX, ROUTE_MODEL, SHAPE_DECODE, OTHER
    }
    private volatile String metadataOutcome = MetadataOutcome.NOT_REQUESTED.name();
    private volatile String metadataValidation = MetadataValidation.NOT_CHECKED.name();
    private volatile long metadataElapsedMillis;
    private volatile int metadataRoutePoints, metadataShapePoints = -1;
    private volatile int metadataGeometryChangedPoints = -1;
    private volatile int metadataGeometryChangedInteriorPoints = -1;
    private volatile long metadataGeometryMaxDeltaMm = -1;
    private volatile boolean metadataDeadlineExpired, metadataCancelRequested;

    public NavigationProviders(NavigationConfig config) { this.config = config; }

    /** Content-free troubleshooting only: never retain destinations, shapes, URLs or error text. */
    public String diagnosticFlags() {
        return "metadata=" + metadataOutcome + ",metadataMs=" + metadataElapsedMillis
                + ",metadataRoutePoints=" + metadataRoutePoints + ",metadataShapePoints=" + metadataShapePoints
                + ",metadataValidation=" + metadataValidation
                + ",metadataGeometryChangedPoints=" + metadataGeometryChangedPoints
                + ",metadataGeometryChangedInteriorPoints=" + metadataGeometryChangedInteriorPoints
                + ",metadataGeometryMaxDeltaMm=" + metadataGeometryMaxDeltaMm;
    }

    /** Called on one dedicated worker. No automatic text-completion or periodic search requests. */
    public List<Place> search(String query, GeoPoint bias) throws IOException, JSONException, InterruptedException {
        if (query == null || query.strip().length() < 2 || query.length() > 300) {
            throw new IllegalArgumentException("Enter a destination between 2 and 300 characters");
        }
        String url = config.searchUrl + (config.searchUrl.contains("?") ? "&" : "?")
                + "q=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8.name()) + "&limit=5";
        if (bias != null) url += "&lat=" + bias.latitude + "&lon=" + bias.longitude;
        return parseSearch(request(url, null));
    }

    public RoutePlan route(GeoPoint origin, Place destination, Float bearing)
            throws IOException, JSONException, InterruptedException {
        JSONObject begin = new JSONObject().put("lat", origin.latitude).put("lon", origin.longitude).put("type", "break");
        if (bearing != null && Float.isFinite(bearing)) begin.put("heading", (bearing % 360 + 360) % 360);
        JSONObject end = new JSONObject().put("lat", destination.point.latitude)
                .put("lon", destination.point.longitude).put("type", "break");
        JSONObject body = new JSONObject().put("locations", new JSONArray().put(begin).put(end))
                .put("costing", "auto").put("units", "kilometers").put("language", "en-US")
                .put("directions_type", "instructions").put("shape_format", "polyline6");
        return parseRoute(request(config.routeUrl, body.toString()), destination.name);
    }

    /** Optional second worker operation after publishing the basic route. No GPS-triggered requests.
     * Missing/unsupported attributes never prevent navigation. A cancellation is not a metadata failure. */
    public RoutePlan enrichRoute(RoutePlan route) throws InterruptedException {
        long began = System.nanoTime();
        MetadataOutcome outcome = MetadataOutcome.RUNNING;
        metadataRoutePoints = route.points.size(); metadataShapePoints = -1;
        metadataValidation = MetadataValidation.NOT_CHECKED.name();
        metadataGeometryChangedPoints = -1; metadataGeometryMaxDeltaMm = -1;
        metadataGeometryChangedInteriorPoints = -1;
        metadataElapsedMillis = 0; metadataDeadlineExpired = false; metadataCancelRequested = false;
        metadataOutcome = outcome.name();
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String endpoint = metadataUrl(config.routeUrl);
            if (endpoint == null) { outcome = MetadataOutcome.SKIPPED_ENDPOINT; return route; }
            if (route.metadataEnriched) { outcome = MetadataOutcome.SKIPPED_ALREADY_ENRICHED; return route; }
            if (route.points.size() > 10000) { outcome = MetadataOutcome.SKIPPED_POINT_LIMIT; return route; }
            JSONObject body = new JSONObject().put("encoded_polyline", RoutePlan.encodePolyline6(route.points))
                    .put("shape_match", "walk_or_snap").put("costing", "auto").put("units", "kilometers")
                    .put("filters", new JSONObject().put("action", "include").put("attributes", new JSONArray()
                            .put("shape").put("edge.begin_shape_index").put("edge.end_shape_index")
                            .put("edge.speed_limit").put("node.elapsed_time")
                            .put("shape_attributes.time").put("shape_attributes.speed_limit")));
            RoutePlan enriched = parseRouteMetadata(request(endpoint, body.toString(), true), route,
                    points -> {
                        metadataShapePoints = points.size();
                        if (points.size() == route.points.size()) {
                            int changed = 0, interiorChanged = 0; double maximum = 0;
                            for (int index = 0; index < points.size(); index++) {
                                GeoPoint expected = route.points.get(index), observed = points.get(index);
                                if (!samePoint(expected, observed)) {
                                    changed++;
                                    if (index > 0 && index < points.size() - 1) interiorChanged++;
                                    maximum = Math.max(maximum, expected.distanceTo(observed));
                                }
                            }
                            metadataGeometryChangedPoints = changed;
                            metadataGeometryChangedInteriorPoints = interiorChanged;
                            metadataGeometryMaxDeltaMm = Math.round(maximum * 1000);
                        }
                    });
            metadataValidation = MetadataValidation.VALID.name();
            outcome = enriched == route ? MetadataOutcome.UNCHANGED : MetadataOutcome.SUCCESS;
            return enriched;
        } catch (InterruptedException cancelled) {
            outcome = MetadataOutcome.CANCELLED;
            throw cancelled;
        } catch (IOException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            outcome = metadataCancelRequested ? MetadataOutcome.CANCELLED
                    : unavailable instanceof HttpStatusException ? MetadataOutcome.HTTP_FAILURE
                    : metadataDeadlineExpired || unavailable instanceof SocketTimeoutException ? MetadataOutcome.TIMEOUT
                    : MetadataOutcome.IO_FAILURE;
            return route;
        } catch (JSONException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            outcome = MetadataOutcome.JSON_FAILURE;
            return route;
        } catch (IllegalArgumentException unavailable) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            metadataValidation = validationCategory(unavailable);
            outcome = MetadataOutcome.INVALID_METADATA;
            return route;
        } finally {
            if (Thread.currentThread().isInterrupted()) outcome = MetadataOutcome.CANCELLED;
            metadataElapsedMillis = Math.max(0, (System.nanoTime() - began) / 1000000);
            metadataOutcome = outcome.name();
        }
    }

    /** Only our own literal validation reasons enter diagnostics; arbitrary text is discarded. */
    static String validationCategory(IllegalArgumentException failure) {
        String message = failure.getMessage();
        if (message == null) return MetadataValidation.OTHER.name();
        return switch (message) {
            case "Optional route metadata geometry differs" -> MetadataValidation.GEOMETRY.name();
            case "Ambiguous optional route metadata geometry" -> MetadataValidation.AMBIGUOUS_GEOMETRY.name();
            case "Optional speed-limit array geometry differs" -> MetadataValidation.LIMIT_ARRAY.name();
            case "Optional travel-time array geometry differs" -> MetadataValidation.TIME_ARRAY.name();
            case "Invalid route metadata edges" -> MetadataValidation.EDGES.name();
            case "Unknown route metadata units" -> MetadataValidation.UNITS.name();
            case "Discontinuous route metadata edges" -> MetadataValidation.EDGE_ORDER.name();
            case "Incomplete route metadata edges" -> MetadataValidation.EDGE_COVERAGE.name();
            case "Invalid route metadata index" -> MetadataValidation.INDEX.name();
            case "Route has invalid geometry, guidance or duration", "Route maneuver indexes are invalid",
                    "Speed-limit geometry mismatch", "Invalid posted speed limit",
                    "Travel-time geometry mismatch", "Invalid segment travel time" -> MetadataValidation.ROUTE_MODEL.name();
            case "Route geometry is too large", "Too many route points", "Truncated route shape",
                    "Invalid route shape character", "Invalid geographic coordinate" -> MetadataValidation.SHAPE_DECODE.name();
            default -> MetadataValidation.OTHER.name();
        };
    }

    /** A compatible sibling action on the configured origin only; preserve a provider's query auth. */
    public static String metadataUrl(String routeUrl) {
        URI route = URI.create(routeUrl);
        if (!"https".equals(route.getScheme()) || route.getHost() == null || route.getRawUserInfo() != null
                || route.getRawFragment() != null || route.getRawPath() == null || !route.getRawPath().endsWith("/route")) return null;
        String endpoint = route.resolve("./trace_attributes").toASCIIString();
        return route.getRawQuery() == null ? endpoint : endpoint + "?" + route.getRawQuery();
    }

    public synchronized void cancel() {
        if (metadataOutcome.equals(MetadataOutcome.RUNNING.name())) metadataCancelRequested = true;
        HttpURLConnection active = connection;
        if (active != null && cancellationQueued != active) {
            cancellationQueued = active;
            // Closing a TLS socket can block; cancellation must never stall a dashboard click.
            CONNECTION_CLEANUP.execute(() -> { try { active.disconnect(); } catch (RuntimeException ignored) { } });
        }
    }

    private String request(String url, String body) throws IOException, InterruptedException {
        return request(url, body, false);
    }

    private String request(String url, String body, boolean metadata) throws IOException, InterruptedException {
        long deadline = metadata ? System.nanoTime() + 4000000000L : Long.MAX_VALUE;
        // Shared worker serializes calls; modest use of community demo services stays below one request/second.
        long waitNanos = 1100000000L - (System.nanoTime() - lastRequestNanos);
        if (waitNanos > 0) Thread.sleep((waitNanos + 999999) / 1000000);
        lastRequestNanos = System.nanoTime();
        if (Thread.interrupted()) throw new InterruptedException();
        HttpURLConnection active = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection = active;
        Timer watchdog = null;
        try {
            if (metadata) {
                long remaining = (deadline - System.nanoTime()) / 1000000;
                if (remaining <= 0) {
                    metadataDeadlineExpired = true;
                    throw new IOException("Optional route metadata timed out");
                }
                watchdog = new Timer("open-auto-route-metadata-deadline", true);
                watchdog.schedule(new TimerTask() {
                    @Override public void run() {
                        metadataDeadlineExpired = true;
                        try { active.disconnect(); } catch (RuntimeException ignored) {}
                    }
                }, remaining);
            }
            active.setConnectTimeout(metadata ? 1500 : 12000);
            active.setReadTimeout(metadata ? 2000 : 18000);
            active.setInstanceFollowRedirects(false);
            active.setRequestProperty("User-Agent", NavigationConfig.USER_AGENT);
            active.setRequestProperty("X-Client-Id", "open-auto-client-research");
            active.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                active.setRequestMethod("POST");
                active.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                active.setDoOutput(true);
                active.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = active.getOutputStream()) { output.write(bytes); }
            }
            int status = active.getResponseCode();
            if (status == 429) throw new HttpStatusException("Provider is busy; wait before trying again");
            if (status != 200) throw new HttpStatusException("Navigation provider returned HTTP " + status);
            try (InputStream input = active.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (metadata && System.nanoTime() >= deadline) {
                        metadataDeadlineExpired = true;
                        throw new IOException("Optional route metadata timed out");
                    }
                    if (output.size() + count > (metadata ? 2000000 : 8000000)) throw new IOException("Navigation response is too large");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            if (watchdog != null) watchdog.cancel();
            active.disconnect();
            if (connection == active) connection = null;
        }
    }

    private static final class HttpStatusException extends IOException {
        HttpStatusException(String message) { super(message); }
    }

    public static List<Place> parseSearch(String json) throws JSONException {
        JSONArray features = new JSONObject(json).getJSONArray("features");
        List<Place> found = new ArrayList<>();
        for (int index = 0; index < Math.min(10, features.length()); index++) {
            JSONObject feature = features.getJSONObject(index);
            JSONObject geometry = feature.getJSONObject("geometry");
            if (!"Point".equals(geometry.optString("type"))) continue;
            JSONArray coordinates = geometry.getJSONArray("coordinates");
            JSONObject props = feature.getJSONObject("properties");
            String name = props.optString("name", props.optString("street", "Destination"));
            String house = props.optString("housenumber");
            String street = props.optString("street");
            String city = props.optString("city", props.optString("county"));
            String state = props.optString("state");
            String detail = (house + " " + street).strip();
            if (!detail.isEmpty() && !detail.equals(name)) name += ", " + detail;
            if (!city.isEmpty() && !city.equals(name)) name += ", " + city;
            if (!state.isEmpty()) name += ", " + state;
            found.add(new Place(name, new GeoPoint(coordinates.getDouble(1), coordinates.getDouble(0))));
        }
        return found;
    }

    public static RoutePlan parseRoute(String json, String destinationName) throws JSONException {
        JSONObject result = new JSONObject(json);
        if (result.has("error")) throw new IllegalArgumentException(result.optString("error", "Routing failed"));
        JSONObject trip = result.getJSONObject("trip");
        if (trip.optInt("status", 0) != 0) throw new IllegalArgumentException(trip.optString("status_message", "No route"));
        JSONArray legs = trip.getJSONArray("legs");
        List<GeoPoint> points = new ArrayList<>();
        List<RoutePlan.Maneuver> maneuvers = new ArrayList<>();
        for (int legIndex = 0; legIndex < legs.length(); legIndex++) {
            JSONObject leg = legs.getJSONObject(legIndex);
            List<GeoPoint> legPoints = RoutePlan.decodePolyline6(leg.getString("shape"));
            if (legPoints.size() < 2) throw new IllegalArgumentException("Route leg has no geometry");
            int offset = points.size();
            if (!points.isEmpty() && points.get(points.size() - 1).distanceTo(legPoints.get(0)) < 1) {
                offset--;
                points.addAll(legPoints.subList(1, legPoints.size()));
            } else points.addAll(legPoints);
            JSONArray steps = leg.getJSONArray("maneuvers");
            for (int step = 0; step < steps.length(); step++) {
                JSONObject item = steps.getJSONObject(step);
                int begin = item.getInt("begin_shape_index");
                int end = item.getInt("end_shape_index");
                if (begin < 0 || end < begin || end >= legPoints.size()) throw new IllegalArgumentException("Invalid maneuver geometry");
                String instruction = item.getString("instruction");
                String spoken = item.optString("verbal_pre_transition_instruction", instruction);
                Object rawTime = item.opt("time");
                double seconds = rawTime instanceof Number ? ((Number) rawTime).doubleValue() : Double.NaN;
                if (!Double.isFinite(seconds) || seconds < 0) seconds = Double.NaN;
                maneuvers.add(new RoutePlan.Maneuver(offset + begin, offset + end, instruction, spoken, item.optInt("type"), seconds));
            }
        }
        return new RoutePlan(points, maneuvers, trip.getJSONObject("summary").getDouble("time"), destinationName);
    }

    /** Native trace serializer uses seconds, and shape speed_limit stays in kph even for units=miles.
     * Its edge speed_limit instead follows units and serializes explicit no-limit as "unlimited".
     * https://github.com/valhalla/valhalla/blob/master/src/tyr/trace_serializer.cc */
    public static RoutePlan parseRouteMetadata(String json, RoutePlan route) throws JSONException {
        return parseRouteMetadata(json, route, null);
    }

    private static RoutePlan parseRouteMetadata(String json, RoutePlan route, Consumer<List<GeoPoint>> geometry) throws JSONException {
        JSONObject result = new JSONObject(json);
        List<GeoPoint> metadataPoints = RoutePlan.decodePolyline6(result.optString("shape"));
        if (geometry != null) geometry.accept(metadataPoints);
        MetadataAlignment alignment = metadataAlignment(route, metadataPoints);
        int offset = alignment.offset;
        int segments = metadataPoints.size() - 1, routeSegments = route.points.size() - 1;
        JSONObject attributes = result.optJSONObject("shape_attributes");
        JSONArray shapeLimits = attributes == null ? null : attributes.optJSONArray("speed_limit");
        JSONArray shapeTimes = attributes == null ? null : attributes.optJSONArray("time");
        if (shapeLimits != null && shapeLimits.length() != segments)
            throw new IllegalArgumentException("Optional speed-limit array geometry differs");
        if (shapeTimes != null && shapeTimes.length() != segments)
            throw new IllegalArgumentException("Optional travel-time array geometry differs");
        double[] limits = new double[routeSegments], times = new double[routeSegments];
        Arrays.fill(limits, Double.NaN);
        if (shapeLimits != null) for (int index = 0; index < segments; index++) limits[index + offset] = postedLimit(shapeLimits.opt(index), false);
        double[] rawTimes = new double[segments];
        boolean rawTimesValid = shapeTimes != null;
        if (shapeTimes != null) for (int index = 0; index < segments; index++) {
            Object value = shapeTimes.opt(index);
            rawTimes[index] = value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
            if (!Double.isFinite(rawTimes[index]) || rawTimes[index] < 0) rawTimesValid = false;
        }
        JSONArray edges = result.getJSONArray("edges");
        if (edges.length() == 0 || edges.length() > segments * 2 + 1) throw new IllegalArgumentException("Invalid route metadata edges");
        String units = result.optString("units", "kilometers");
        if (!units.equals("kilometers") && !units.equals("miles")) throw new IllegalArgumentException("Unknown route metadata units");
        boolean miles = units.equals("miles"), timingValid = true;
        double trimmedSeconds = 0;
        // A map-matching fallback can omit a tiny endpoint segment. Preserve its existing
        // provider timing if available, and never extend a posted limit into that segment.
        for (int segment = 0; segment < routeSegments; segment++) {
            if (segment >= offset && segment < offset + segments) continue;
            double duration = route.remainingSecondsAt(route.cumulativeMeters[segment])
                    - route.remainingSecondsAt(route.cumulativeMeters[segment + 1]);
            if (!Double.isFinite(duration) || duration < 0) timingValid = false;
            else { times[segment] = duration; trimmedSeconds += duration; }
        }
        int next = 0;
        double elapsed = 0, pendingDelay = 0;
        for (int index = 0; index < edges.length(); index++) {
            JSONObject edge = edges.getJSONObject(index);
            int begin = strictIndex(edge, "begin_shape_index"), end = strictIndex(edge, "end_shape_index");
            if (begin != next || end < begin || end > segments) throw new IllegalArgumentException("Discontinuous route metadata edges");
            if (shapeLimits == null) {
                double limit = postedLimit(edge.opt("speed_limit"), miles);
                for (int segment = begin; segment < end; segment++) limits[segment + offset] = limit;
            }
            JSONObject node = edge.optJSONObject("end_node");
            Object rawElapsed = node == null ? null : node.opt("elapsed_time");
            double endTime = rawElapsed instanceof Number ? ((Number) rawElapsed).doubleValue() : Double.NaN;
            if (!Double.isFinite(endTime) || endTime < elapsed || endTime > 31 * 86400) timingValid = false;
            if (timingValid) {
                double delta = endTime - elapsed;
                if (begin == end) pendingDelay += delta;
                else {
                    double rawSum = 0;
                    if (rawTimesValid) for (int segment = begin; segment < end; segment++) rawSum += rawTimes[segment];
                    double distance = route.cumulativeMeters[end + offset] - route.cumulativeMeters[begin + offset];
                    if (rawTimesValid && rawSum > 0) {
                        double scale = rawSum > delta ? delta / rawSum : 1;
                        for (int segment = begin; segment < end; segment++) times[segment + offset] = rawTimes[segment] * scale;
                        // Shape times omit transition delays; preserve node elapsed costs at the edge entry.
                        times[begin + offset] += Math.max(0, delta - rawSum) + pendingDelay;
                    } else if (distance > 0) {
                        for (int segment = begin; segment < end; segment++) times[segment + offset] = delta
                                * (route.cumulativeMeters[segment + offset + 1] - route.cumulativeMeters[segment + offset]) / distance;
                        times[begin + offset] += pendingDelay;
                    } else timingValid = false;
                    pendingDelay = 0;
                }
                elapsed = endTime;
            }
            next = end;
        }
        if (next != segments) throw new IllegalArgumentException("Incomplete route metadata edges");
        if (timingValid && pendingDelay > 0) times[offset + segments - 1] += pendingDelay;
        if (!timingValid || Math.abs(elapsed + trimmedSeconds - route.seconds) > Math.max(5, route.seconds * .02)) times = null;
        // A reprojected endpoint does not prove the affected segment's posted restriction.
        if (alignment.firstReprojected) limits[0] = Double.NaN;
        if (alignment.lastReprojected) limits[routeSegments - 1] = Double.NaN;
        boolean knownLimit = false;
        for (double limit : limits) if (!Double.isNaN(limit)) { knownLimit = true; break; }
        return times != null || knownLimit ? route.withRoadMetadata(times, limits) : route;
    }

    /** Interior geometry always matches exactly and uniquely. A map-matching fallback can
     * either omit an exact endpoint path of at most 5m, or reproject an equal-sized shape's
     * first/last point by at most 1m. Reprojection never supplies its affected segment's limit. */
    private static MetadataAlignment metadataAlignment(RoutePlan route, List<GeoPoint> metadata) {
        int count = metadata.size();
        if (count < 2 || count > route.points.size())
            throw new IllegalArgumentException("Optional route metadata geometry differs");
        int offset = uniqueOffset(route.points, metadata);
        if (offset >= 0) {
            if (route.cumulativeMeters[offset] > 5
                    || route.lengthMeters() - route.cumulativeMeters[offset + count - 1] > 5)
                throw new IllegalArgumentException("Optional route metadata geometry differs");
            return new MetadataAlignment(offset, false, false);
        }
        if (count != route.points.size() || count < 3)
            throw new IllegalArgumentException("Optional route metadata geometry differs");
        for (int index = 1; index < count - 1; index++) {
            if (!samePoint(route.points.get(index), metadata.get(index)))
                throw new IllegalArgumentException("Optional route metadata geometry differs");
        }
        if (route.points.get(0).distanceTo(metadata.get(0)) > 1
                || route.destination().distanceTo(metadata.get(count - 1)) > 1)
            throw new IllegalArgumentException("Optional route metadata geometry differs");
        if (uniqueOffset(route.points, metadata.subList(1, count - 1)) != 1)
            throw new IllegalArgumentException("Optional route metadata geometry differs");
        return new MetadataAlignment(0, !samePoint(route.points.get(0), metadata.get(0)),
                !samePoint(route.destination(), metadata.get(count - 1)));
    }

    private static final class MetadataAlignment {
        final int offset;
        final boolean firstReprojected, lastReprojected;
        MetadataAlignment(int offset, boolean firstReprojected, boolean lastReprojected) {
            this.offset = offset; this.firstReprojected = firstReprojected; this.lastReprojected = lastReprojected;
        }
    }

    private static int uniqueOffset(List<GeoPoint> original, List<GeoPoint> metadata) {
        int count = metadata.size();
        // Linear subsequence matching also detects repeated/ambiguous route passages.
        int[] prefix = new int[count];
        for (int index = 1, matched = 0; index < count; index++) {
            while (matched > 0 && !samePoint(metadata.get(index), metadata.get(matched))) matched = prefix[matched - 1];
            if (samePoint(metadata.get(index), metadata.get(matched))) matched++;
            prefix[index] = matched;
        }
        int offset = -1;
        for (int index = 0, matched = 0; index < original.size(); index++) {
            while (matched > 0 && !samePoint(original.get(index), metadata.get(matched))) matched = prefix[matched - 1];
            if (samePoint(original.get(index), metadata.get(matched))) matched++;
            if (matched == count) {
                if (offset >= 0) throw new IllegalArgumentException("Ambiguous optional route metadata geometry");
                offset = index + 1 - count;
                matched = prefix[matched - 1];
            }
        }
        return offset;
    }

    private static boolean samePoint(GeoPoint a, GeoPoint b) {
        return a.latitude == b.latitude && a.longitude == b.longitude;
    }

    private static int strictIndex(JSONObject object, String name) throws JSONException {
        Object raw = object.get(name);
        if (!(raw instanceof Number)) throw new IllegalArgumentException("Invalid route metadata index");
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < 0 || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid route metadata index");
        return (int) value;
    }

    private static double postedLimit(Object raw, boolean miles) {
        if ("unlimited".equals(raw)) return Double.POSITIVE_INFINITY;
        if (!(raw instanceof Number)) return Double.NaN;
        double value = ((Number) raw).doubleValue();
        if (value == 255 && !miles) return Double.POSITIVE_INFINITY;
        if (!Double.isFinite(value) || value <= 0 || value >= 255) return Double.NaN;
        value *= miles ? 1.609344 : 1;
        return value > 0 && value < 255 ? value : Double.NaN;
    }
}
