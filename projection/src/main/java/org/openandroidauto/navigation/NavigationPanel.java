package org.openandroidauto.navigation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Build;
import java.io.File;
import android.view.Gravity;
import android.view.MotionEvent;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import org.openandroidauto.ui.CarTextKeyboard;
import org.openandroidauto.ui.CarUi;
import org.openandroidauto.render.FrameCadenceCounters;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Real map/search/route prototype for a window owned by our projection client. Does not use Google's
 * car-app host or screen capture. The caller owns lifecycle and forwards projected touch to this view.
 */
public final class NavigationPanel extends FrameLayout {
    public interface Listener {
        default void onNavigationInstruction(String text, double distanceMeters) {}
        default void onStatus(String text) {}
        default void onDestinationSelected(String name, double latitude, double longitude) {}
        /** The receiver owns deletion after playback; no phone-speaker fallback. */
        default void onSpeechAudioFile(File file) { file.delete(); }
        default void onSpeechCancelled() {}
        default boolean onDictatePlaceSearchRequested(long token) { return false; }
        default void onDictationCancelled(long token) {}
        default void onCancelableFlowChanged(boolean active) {}
    }

    private static final String ROUTE_SOURCE = "open-auto-route";
    private static final String POSITION_SOURCE = "open-auto-position";
    private static final String DESTINATION_SOURCE = "open-auto-destination";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "open-auto-navigation"));
    private final ExecutorService handoffWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "open-auto-online-route"));
    private final OnlineRouteRefreshPolicy onlineRefresh = new OnlineRouteRefreshPolicy();
    private final Runnable refreshOnlineRoute = this::refreshOnlineRoute;
    private final DefaultInternetMonitor internet;
    private final OfflineNavigationAccess localNavigation;
    private final MapView mapView;
    private final TextView instruction;
    private final ManeuverIconView maneuverIcon;
    private final TextView detail;
    private final EditText query;
    private final Button search;
    private final Button stopRoute;
    private final Button recenter;
    private final LinearLayout results;
    private final ScrollView resultScroller;
    private final MapAttributionSheet attributionSheet;
    private final SpeedTileView speedTile;
    private SpeedReadout.Observation speedObservation;
    private SpeedReadout.Observation roadObservation;
    private Location speedLocation;
    private RouteTracker.Progress lastProgress;
    private boolean showingTripSummary;
    private final Runnable expireSpeed = this::refreshSpeedTile;
    private final Runnable refreshTripClock = this::refreshTripClock;
    private CarTextKeyboard keyboard;
    private final LocationManager locations;
    private final NavigationSpeech speech;
    private final DestinationStore destinations;
    private final NavigationSearchGate placeSearches = new NavigationSearchGate();
    private Button saveDestination;
    private LinearLayout banner;
    private LinearLayout searchBar;
    private NavigationConfig config;
    private AdaptiveMapFramePolicy framePolicy;
    private int appliedMaximumFps = -1;
    private final Runnable reevaluateFrames = this::applyFrameDecision;
    private NavigationProviders providers;
    private NavigationProviders routeProviders;
    private NavigationProviders handoffProviders;
    private Listener listener;
    private MapLibreMap map;
    private Style style;
    private RoutePlan route;
    private RouteTracker tracker;
    private NavigationProviders.Place destination;
    private NavigationProviders.Place routeDestination;
    private Location lastLocation;
    private final MovingCourseHeading cameraHeading = new MovingCourseHeading();
    private final TravelHeading travelHeading = new TravelHeading();
    private final PositionPuck positionPuck = new PositionPuck();
    private boolean headingSourceInitialized;
    private boolean headingSourceSynthetic;
    private boolean headingSourceMock;
    private GeoPoint position;
    private Future<?> pending;
    private Future<?> handoffPending;
    private long generation;
    private long routeRevision;
    private long lastRerouteMillis = -30000;
    private int spokenManeuver = -1;
    private int spokenBand = -1;
    private boolean started;
    private boolean destroyed;
    private boolean renderingSuspended;
    private boolean mapResumed;
    private boolean following = true;
    private boolean routeInFlight;
    private boolean replay;
    private boolean speechSuppressed;
    private boolean announcementSpeech;
    private boolean cancelableFlowReported;
    private String lastStatus = "";
    private volatile long renderedMapFrames;
    private volatile boolean fullyRenderedMapFrame;
    private volatile FrameCadenceCounters.Session frameCounterSession = FrameCadenceCounters.disabledSession();
    private boolean frameCounterBound;

    public NavigationPanel(Context context) { this(context, new Listener() {}); }

    public NavigationPanel(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        destinations = DestinationPreferences.open(context);
        speech = new NavigationSpeech(context, new NavigationSpeech.Listener() {
            @Override public void onAudioFile(File file) {
                if (destroyed || speechSuppressed) { file.delete(); return; }
                NavigationPanel.this.listener.onSpeechAudioFile(file);
            }
            @Override public void onStatus(String text) { if (!destroyed) status(text); }
            @Override public void onCancelled() { announcementSpeech = false; NavigationPanel.this.listener.onSpeechCancelled(); }
        });
        config = NavigationConfig.load(context);
        framePolicy = config.createFramePolicy();
        providers = new NavigationProviders(config);
        internet = new DefaultInternetMonitor(context, main, this::internetChanged);
        localNavigation = new OfflineNavigationAccess(context);
        locations = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        MapLibre.getInstance(context.getApplicationContext());
        org.maplibre.android.module.http.HttpRequestUtil.setLogEnabled(false);
        setBackgroundColor(CarUi.BACKGROUND);
        MapLibreMapOptions options = new MapLibreMapOptions().textureMode(true).compassEnabled(false)
                // Our credits and information control use the dashboard's existing window.
                .rotateGesturesEnabled(false).attributionEnabled(false);
        mapView = new MapView(context, options);
        mapView.onCreate(null);
        applyFrameDecision();
        mapView.addOnDidFinishRenderingFrameListener((fully, encodingTime, renderingTime) -> {
            renderedMapFrames++;
            if (fully) fullyRenderedMapFrame = true;
            FrameCadenceCounters.Session counters = frameCounterSession;
            counters.mapFrameRendered(fully);
        });
        mapView.addOnDidFailLoadingMapListener(error -> main.post(() -> {
            if (!destroyed) status("Map unavailable: " + error);
        }));
        addView(mapView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        banner = new LinearLayout(context);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(dp(14), dp(10), dp(14), dp(10));
        banner.setBackground(CarUi.rounded(context, CarUi.SURFACE, 16));
        banner.setElevation(dp(4));
        instruction = label("", 21);
        instruction.setMaxLines(2); instruction.setEllipsize(TextUtils.TruncateAt.END);
        maneuverIcon = new ManeuverIconView(context);
        maneuverIcon.setVisibility(GONE);
        detail = label("", 13); detail.setTextColor(CarUi.TEXT_MUTED);
        detail.setMaxLines(1); detail.setEllipsize(TextUtils.TruncateAt.END);
        instruction.setVisibility(GONE);
        LinearLayout maneuverRow = new LinearLayout(context);
        maneuverRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        iconParams.rightMargin = dp(10);
        maneuverRow.addView(maneuverIcon, iconParams);
        maneuverRow.addView(instruction, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        banner.addView(maneuverRow);
        banner.addView(detail);
        LayoutParams bannerParams = new LayoutParams(dp(390), LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        bannerParams.leftMargin = dp(8); bannerParams.topMargin = dp(60);
        banner.setVisibility(GONE);
        addView(banner, bannerParams);

        searchBar = new LinearLayout(context);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setPadding(dp(6), 0, dp(2), 0);
        searchBar.setBackground(CarUi.rounded(context, CarUi.SURFACE, 18));
        searchBar.setElevation(dp(4));
        query = new EditText(context);
        query.setSingleLine(true);
        query.setShowSoftInputOnFocus(false);
        query.setFilters(new InputFilter[] {new InputFilter.LengthFilter(CarTextKeyboard.MAX_CHARACTERS)});
        query.setOnClickListener(view -> showSearchKeyboard());
        query.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && event.getX() >= 0 && event.getX() < view.getWidth()
                    && event.getY() >= 0 && event.getY() < view.getHeight()) view.performClick();
            // EditText may use its first tap only for focus. Keep its normal selection handling.
            return false;
        });
        query.setTextColor(CarUi.TEXT);
        query.setHintTextColor(CarUi.TEXT_MUTED);
        query.setTextSize(17);
        query.setHint("Search places");
        query.setPadding(dp(8), 0, dp(4), 0); query.setBackgroundColor(Color.TRANSPARENT);
        query.setMinWidth(0); query.setMinimumWidth(0);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBar.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
        search = button("Find", () -> search(query.getText().toString()));
        CarUi.applyButton(search, true);
        search.setSingleLine(true); search.setTextSize(14); search.setPadding(dp(6), 0, dp(6), 0);
        searchBar.addView(search, new LinearLayout.LayoutParams(dp(56), dp(48)));
        searchBar.addView(button("Saved", () -> showDestinations(false)), new LinearLayout.LayoutParams(dp(60), dp(48)));
        searchBar.addView(button("Recent", () -> showDestinations(true)), new LinearLayout.LayoutParams(dp(64), dp(48)));
        searchBar.addView(button("Dictate", () -> {
            Long token = prepareDictationSearch();
            if (token != null && !NavigationPanel.this.listener.onDictatePlaceSearchRequested(token)) {
                cancelDictationSearch(token); status("Set up optional voice input on the phone to dictate a place");
            }
        }), new LinearLayout.LayoutParams(dp(76), dp(48)));
        LayoutParams searchParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP | Gravity.LEFT);
        searchParams.leftMargin = dp(8); searchParams.rightMargin = dp(8); searchParams.topMargin = dp(4);
        addView(searchBar, searchParams);
        LinearLayout mapActions = new LinearLayout(context);
        mapActions.setOrientation(LinearLayout.VERTICAL);
        recenter = button("Follow", () -> { following = true; updateMap(false); });
        mapActions.addView(recenter, new LinearLayout.LayoutParams(dp(72), dp(48)));
        stopRoute = button("End route", this::stopNavigation);
        stopRoute.setVisibility(GONE);
        mapActions.addView(stopRoute, new LinearLayout.LayoutParams(dp(72), dp(48)));
        saveDestination = button("Save", () -> { if (destination != null) editDestination(destination); });
        saveDestination.setVisibility(GONE);
        mapActions.addView(saveDestination, new LinearLayout.LayoutParams(dp(72), dp(48)));
        mapActions.setBackground(CarUi.rounded(context, CarUi.SURFACE, 16)); mapActions.setElevation(dp(3));
        LayoutParams actionsParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM);
        actionsParams.bottomMargin = dp(100); actionsParams.rightMargin = dp(8);
        addView(mapActions, actionsParams);
        speedTile = new SpeedTileView(context);
        LayoutParams speedParams = new LayoutParams(dp(156), dp(72), Gravity.BOTTOM | Gravity.RIGHT);
        speedParams.rightMargin = dp(8); speedParams.bottomMargin = dp(22);
        addView(speedTile, speedParams);
        refreshSpeedTile();
        TextView attribution = label("© OpenMapTiles.org · © OpenStreetMap", 12);
        attribution.setGravity(Gravity.CENTER); attribution.setBackgroundColor(0xd9172029);
        attribution.setIncludeFontPadding(false); attribution.setSingleLine(true);
        attribution.setPadding(dp(5), 0, dp(5), 0);
        attribution.setContentDescription("Map data from OpenStreetMap contributors and OpenMapTiles. Open map information for data licenses.");
        attribution.setOnClickListener(view -> showAttribution());
        addView(attribution, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(18), Gravity.BOTTOM | Gravity.RIGHT));
        Button mapInformation = button("ⓘ", this::showAttribution);
        mapInformation.setTextSize(24);
        mapInformation.setContentDescription("Map information and data licenses");
        LayoutParams infoParams = new LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.RIGHT);
        infoParams.rightMargin = dp(176); infoParams.bottomMargin = dp(34);
        addView(mapInformation, infoParams);

        results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(dp(4), dp(2), dp(4), dp(4));
        results.setBackground(CarUi.rounded(context, CarUi.SURFACE, 16));
        resultScroller = new ScrollView(context);
        resultScroller.addView(results);
        resultScroller.setVisibility(GONE);
        resultScroller.setElevation(dp(6)); resultScroller.setFillViewport(false);
        LayoutParams resultParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP);
        resultParams.topMargin = dp(60); resultParams.bottomMargin = dp(80);
        resultParams.leftMargin = dp(8); resultParams.rightMargin = dp(8);
        addView(resultScroller, resultParams);

        attributionSheet = new MapAttributionSheet(context, this::hideAttribution);
        LayoutParams attributionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(attributionSheet, attributionParams);

        mapView.getMapAsync(ready -> {
            if (destroyed) return;
            map = ready;
            map.getUiSettings().setLogoEnabled(false);
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) following = false;
                framePolicy.setCameraMoving(true, SystemClock.uptimeMillis()); applyFrameDecision();
            });
            map.addOnCameraIdleListener(() -> { framePolicy.setCameraMoving(false, SystemClock.uptimeMillis()); applyFrameDecision(); });
            loadStyle();
        });
        query.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(query.getText().toString()); return true; }
            return false;
        });
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                cancelPlaceInteraction(); resultScroller.setVisibility(GONE); refreshBanner();
            }
            @Override public void afterTextChanged(Editable text) {}
        });

    }

    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {} : listener;
        this.listener.onCancelableFlowChanged(hasCancelableFlow());
    }
    public NavigationConfig getConfig() { return config; }
    public RoutePlan getRoute() { return route; }
    public GeoPoint getPosition() { return position; }
    public String getStatus() { return lastStatus; }
    public long getRenderedMapFrames() { return renderedMapFrames; }
    public boolean hasFullyRenderedMapFrame() { return fullyRenderedMapFrame; }
    /** A specific projection session is supplied once by the owning dashboard; previews stay disabled. */
    public void setFrameCadenceSession(FrameCadenceCounters.Session session) {
        if (started || destroyed || frameCounterBound) return;
        frameCounterBound = true;
        frameCounterSession = session == null ? FrameCadenceCounters.disabledSession() : session;
    }

    /** Observe interaction without consuming or modifying MapLibre's gestures or child clicks. */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (framePolicy != null && started && !renderingSuspended && !destroyed) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) framePolicy.setInteracting(true, SystemClock.uptimeMillis());
            else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                framePolicy.setInteracting(false, SystemClock.uptimeMillis());
            applyFrameDecision();
        }
        return super.dispatchTouchEvent(event);
    }
    private void updateFrameVisibility() {
        framePolicy.setVisible(started && !renderingSuspended && !destroyed, SystemClock.uptimeMillis());
        applyFrameDecision();
    }
    private void noteFrameActivity() { framePolicy.noteActivity(SystemClock.uptimeMillis()); applyFrameDecision(); }
    private void applyFrameDecision() {
        main.removeCallbacks(reevaluateFrames);
        if (destroyed || framePolicy == null || mapView == null) return;
        long now = SystemClock.uptimeMillis();
        AdaptiveMapFramePolicy.Decision decision = framePolicy.evaluate(now);
        if (appliedMaximumFps != decision.maximumFps) {
            mapView.setMaximumFps(decision.maximumFps); appliedMaximumFps = decision.maximumFps;
        }
        if (started && !renderingSuspended && decision.nextReevaluateAtMillis > now)
            main.postAtTime(reevaluateFrames, decision.nextReevaluateAtMillis);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (searchBar == null) return;
        int contentWidth = Math.min(Math.max(0, width - dp(16)), dp(720));
        LayoutParams searchParams = (LayoutParams) searchBar.getLayoutParams();
        searchParams.width = contentWidth; searchBar.setLayoutParams(searchParams);
        LayoutParams bannerParams = (LayoutParams) banner.getLayoutParams();
        bannerParams.width = Math.min(Math.max(0, width - dp(104)), dp(390)); banner.setLayoutParams(bannerParams);
        LayoutParams resultParams = (LayoutParams) resultScroller.getLayoutParams();
        resultParams.width = contentWidth; resultScroller.setLayoutParams(resultParams);
        if (keyboard != null) {
            LayoutParams keyboardParams = (LayoutParams) keyboard.getLayoutParams();
            keyboardParams.height = keyboardHeight(); keyboard.setLayoutParams(keyboardParams);
        }
    }

    /** Guidance can keep tracking while a media page is visible, without drawing an invisible map. */
    public void setRenderingSuspended(boolean suspended) {
        if (suspended) { cancelCommandFlow(); hideSearchKeyboard(); }
        renderingSuspended = suspended;
        updateFrameVisibility();
        if (!started || destroyed) return;
        if (suspended && mapResumed) {
            mapView.onPause();
            mapResumed = false;
        } else if (!suspended && !mapResumed) {
            mapView.onResume();
            mapResumed = true;
            updateMap(true);
        }
    }

    /** Reconfigure only while stopped so an old provider cannot complete into a new session. */
    public void setConfig(NavigationConfig next) {
        cancelCommandFlow();
        cancelPending();
        invalidateOnlineHandoff();
        config = next;
        config.save(getContext());
        providers = new NavigationProviders(config);
        routeProviders = null;
        framePolicy = config.createFramePolicy(); updateFrameVisibility();
        loadStyle();
    }

    public void setSpeechSuppressed(boolean suppressed) {
        speechSuppressed = suppressed;
        if (suppressed) cancelSpeech();
    }

    public void start() {
        if (!org.openandroidauto.integration.ProjectionAvailability.isSupported()) return;
        if (started || destroyed) return;
        started = true;
        travelHeading.reset();
        headingSourceInitialized = false;
        updatePositionPuck();
        internet.start();
        refreshSpeedTile();
        updateFrameVisibility();
        mapView.onStart();
        if (!renderingSuspended) { mapView.onResume(); mapResumed = true; }
        if (getContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status("Allow precise location on the phone to navigate");
            return;
        }
        if (locations == null) { status("This device has no location service"); return; }
        try {
            for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (locations.isProviderEnabled(provider)) {
                    // Keep real GPS requested at 1 Hz, but accept fresher fixes already
                    // supplied by another client (including the desktop mock companion).
                    locations.requestLocationUpdates(provider, new LocationRequest.Builder(1000)
                            .setMinUpdateIntervalMillis(200).setMinUpdateDistanceMeters(0).build(),
                            getContext().getMainExecutor(), locationListener);
                    Location last = locations.getLastKnownLocation(provider);
                    if (last != null && SystemClock.elapsedRealtimeNanos() - last.getElapsedRealtimeNanos() < 120000000000L
                            && (lastLocation == null || last.getElapsedRealtimeNanos() > lastLocation.getElapsedRealtimeNanos())) {
                        receiveLocation(last, false);
                    }
                }
            }
            if (position == null) status("Waiting for a GPS fix");
        } catch (SecurityException error) { status("Precise location access is unavailable"); }
    }

    public void stop() {
        cancelCommandFlow();
        hideSearchKeyboard();
        if (!started) return;
        started = false;
        internet.stop();
        onlineRefresh.setOnline(false, SystemClock.elapsedRealtime());
        invalidateOnlineHandoff();
        main.removeCallbacks(expireSpeed);
        main.removeCallbacks(refreshTripClock);
        updateFrameVisibility();
        cancelPending();
        if (locations != null) locations.removeUpdates(locationListener);
        stopReplay();
        cancelSpeech();
        if (mapResumed) { mapView.onPause(); mapResumed = false; }
        mapView.onStop();
    }

    public void onDestroy() {
        if (destroyed) return;
        stop();
        destroyed = true;
        main.removeCallbacks(reevaluateFrames);
        cancelPending();
        worker.shutdownNow();
        handoffWorker.shutdownNow();
        speech.close();
        mapView.onDestroy();
    }

    public void onLowMemory() { mapView.onLowMemory(); }

    public void search(String text) { runPlaceSearch(text, false); }

    private boolean runPlaceSearch(String text, boolean requireConfirmation) {
        hideSearchKeyboard();
        String safe = NavigationSearchGate.searchQuery(text);
        if (!started || destroyed || safe == null) return false;
        cancelPlaceInteraction();
        cancelPending();
        long token = generation;
        GeoPoint bias = position;
        NavigationProviders network = providers;
        boolean online = internet.isOnline();
        NavigationSearchGate.Candidates candidates = placeSearches.beginCandidates(requireConfirmation);
        reportCancelableFlow();
        status("Searching…");
        pending = worker.submit(() -> {
            try {
                List<NavigationProviders.Place> found = searchPlaces(network, safe, bias, online);
                main.post(() -> {
                    if (!current(token) || !placeSearches.current(candidates)) return;
                    results.removeAllViews();
                    addPickerHeader(requireConfirmation ? "Choose a destination" : "Search results", false);
                    for (NavigationProviders.Place place : found) addPlaceRow(place, true, candidates);
                    if (found.isEmpty()) { placeSearches.invalidate(); reportCancelableFlow(); }
                    resultScroller.setVisibility(found.isEmpty() ? GONE : VISIBLE);
                    status(found.isEmpty() ? "No places found; try a fuller address" : "Choose a search result");
                });
            } catch (Exception error) {
                if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) return;
                main.post(() -> {
                    if (current(token) && placeSearches.current(candidates)) {
                        placeSearches.invalidate(); reportCancelableFlow();
                        status(online ? "Search unavailable; try again" : "Search unavailable; download places on the phone first");
                    }
                });
            }
        });
        return true;
    }

    /** Explicit normal dictation searches only; a returned transcript can never start a route. */
    public Long prepareDictationSearch() {
        if (!started || destroyed || renderingSuspended) return null;
        cancelPlaceInteraction(); hideSearchKeyboard(); resultScroller.setVisibility(GONE); refreshBanner();
        long token = placeSearches.prepareDictation(); reportCancelableFlow(); return token;
    }
    public boolean acceptTranscript(long token, String text) {
        String safe = NavigationSearchGate.searchQuery(text);
        if (!started || destroyed || renderingSuspended || safe == null || !placeSearches.consumeDictation(token)) return false;
        query.setText(safe); query.setSelection(query.length());
        return runPlaceSearch(safe, false);
    }
    public void cancelDictationSearch(Long token) {
        long previous = placeSearches.pendingDictation();
        placeSearches.cancelDictation(token);
        reportCancelableFlow();
        if (previous != 0 && placeSearches.pendingDictation() != previous) listener.onDictationCancelled(previous);
    }
    /** Parsed directions commands still require candidate selection and a separate Start route action. */
    public boolean beginCommandSearch(String text) {
        String safe = NavigationSearchGate.searchQuery(text);
        if (!started || destroyed || renderingSuspended || safe == null) return false;
        query.setText(safe); query.setSelection(query.length());
        return runPlaceSearch(safe, true);
    }
    public void cancelCommandFlow() {
        hideAttribution();
        cancelPlaceInteraction();
        resultScroller.setVisibility(GONE); results.removeAllViews(); refreshBanner();
    }
    /** Dismiss transient search UI without ending a route that is already active. */
    public boolean handleBackNavigation() {
        if (!started || destroyed || renderingSuspended) return false;
        if (attributionSheet.getVisibility() == VISIBLE) { hideAttribution(); return true; }
        if (keyboard != null && keyboard.getVisibility() == VISIBLE) {
            hideSearchKeyboard(); query.clearFocus(); return true;
        }
        if (resultScroller.getVisibility() == VISIBLE || hasCancelableFlow()
                || routeInFlight || pending != null && !pending.isDone()) {
            cancelPending(); cancelCommandFlow(); query.clearFocus(); return true;
        }
        return false;
    }
    private void cancelPlaceInteraction() {
        long previous = placeSearches.pendingDictation();
        boolean command = placeSearches.hasCommandFlow();
        placeSearches.invalidate();
        reportCancelableFlow();
        if (command) status("Place selection cancelled");
        if (previous != 0) listener.onDictationCancelled(previous);
    }
    public boolean hasCancelableFlow() { return started && !destroyed && !renderingSuspended
            && (placeSearches.hasCancelableFlow() || attributionSheet.getVisibility() == VISIBLE); }
    private void showAttribution() {
        if (destroyed || renderingSuspended) return;
        hideSearchKeyboard(); query.clearFocus();
        attributionSheet.show(style);
        noteFrameActivity(); reportCancelableFlow();
    }
    private void hideAttribution() {
        if (attributionSheet == null || attributionSheet.getVisibility() != VISIBLE) return;
        attributionSheet.setVisibility(GONE);
        noteFrameActivity(); reportCancelableFlow();
    }
    private void reportCancelableFlow() {
        boolean active = hasCancelableFlow();
        if (active != cancelableFlowReported) { cancelableFlowReported = active; listener.onCancelableFlowChanged(active); }
    }

    public void navigateTo(NavigationProviders.Place place) {
        cancelPlaceInteraction();
        try { destinations.recordSelection(place.name.length() > 200 ? place.name.substring(0, 200) : place.name, place.point.latitude, place.point.longitude); }
        catch (RuntimeException error) { status("Recent destination could not be saved"); }
        if (position == null) { status("Wait for a GPS fix, then choose the destination again"); return; }
        destination = place;
        saveDestination.setVisibility(VISIBLE);
        resultScroller.setVisibility(GONE);
        listener.onDestinationSelected(place.name, place.point.latitude, place.point.longitude);
        requestRoute(false);
    }

    public void navigateTo(String name, double latitude, double longitude) {
        navigateTo(new NavigationProviders.Place(name, new GeoPoint(latitude, longitude)));
    }

    public void stopNavigation() {
        cancelCommandFlow();
        hideSearchKeyboard();
        cancelPending();
        invalidateOnlineHandoff();
        stopReplay();
        route = null;
        tracker = null;
        lastProgress = null;
        roadObservation = null;
        refreshSpeedTile();
        destination = null;
        routeDestination = null;
        routeProviders = null;
        spokenManeuver = -1;
        spokenBand = -1;
        cancelSpeech();
        instruction.setText("Choose a destination");
        instruction.setVisibility(GONE);
        maneuverIcon.setVisibility(GONE);
        stopRoute.setVisibility(GONE);
        saveDestination.setVisibility(GONE);
        status("Navigation stopped");
        updateMap(true);
    }

    /** Explicit test mode: advances along real route geometry without changing system GPS settings. */
    public void startRouteReplay(double metersPerSecond) {
        if (route == null || !started || metersPerSecond <= 0 || metersPerSecond > 60) return;
        stopReplay();
        cancelOnlineHandoff();
        replay = true;
        tracker = new RouteTracker(route);
        final RoutePlan replayRoute = route;
        final long began = SystemClock.elapsedRealtime();
        replayTask = new Runnable() {
            @Override public void run() {
                if (!replay || !started || route != replayRoute) return;
                double distance = (SystemClock.elapsedRealtime() - began) * metersPerSecond / 1000;
                GeoPoint simulated = interpolate(replayRoute, Math.min(distance, replayRoute.lengthMeters()));
                Location sample = new Location("open-auto-route-replay");
                sample.setLatitude(simulated.latitude);
                sample.setLongitude(simulated.longitude);
                sample.setAccuracy(5);
                sample.setSpeed((float) metersPerSecond);
                sample.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                receiveLocation(sample, true);
                if (distance < replayRoute.lengthMeters()) main.postDelayed(this, 1000);
                else { replay = false; status("Route replay finished; waiting for real GPS"); }
            }
        };
        main.post(replayTask);
    }

    private Runnable replayTask;
    public void stopReplay() {
        replay = false;
        if (replayTask != null) main.removeCallbacks(replayTask);
        replayTask = null;
    }

    /** Test-only injection into this navigator; never writes or mocks Android's shared location service. */
    public void injectReplayLocation(double latitude, double longitude, float accuracyMeters) {
        cancelOnlineHandoff();
        replay = true;
        Location sample = new Location("open-auto-route-replay");
        sample.setLatitude(latitude); sample.setLongitude(longitude); sample.setAccuracy(accuracyMeters);
        sample.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        receiveLocation(sample, true);
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) { if (!replay) receiveLocation(location, false); }
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) { status("Location provider disabled"); }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };

    private void receiveLocation(Location location, boolean synthetic) {
        if (!started || destroyed) return;
        if (!synthetic && lastLocation != null && location.getElapsedRealtimeNanos() <= lastLocation.getElapsedRealtimeNanos()) return;
        if (!location.hasAccuracy() || location.getAccuracy() > 100) { status("Waiting for a more accurate GPS fix"); return; }
        // Speed freshness is independent of the map's existing stationary/jitter filter.
        // Updating this passive readout does not create an extra map fix or network request.
        long sampleMillis = location.getElapsedRealtimeNanos() / 1000000;
        boolean headingChanged = false;
        if (speedObservation == null || sampleMillis > speedObservation.elapsedMillis) {
            speedObservation = new SpeedReadout.Observation(sampleMillis, location.hasSpeed(), location.getSpeed(),
                    location.getAccuracy(), location.hasSpeedAccuracy(), location.getSpeedAccuracyMetersPerSecond());
            speedLocation = new Location(location);
            roadObservation = null;
            long now = SystemClock.elapsedRealtime();
            if (sampleMillis >= 0 && sampleMillis <= now && now - sampleMillis <= 15000
                    && Double.isFinite(location.getLatitude()) && Math.abs(location.getLatitude()) <= 90
                    && Double.isFinite(location.getLongitude()) && Math.abs(location.getLongitude()) <= 180) {
                if (headingSourceInitialized && (headingSourceSynthetic != synthetic || headingSourceMock != isMockLocation(location))) {
                    travelHeading.reset();
                    headingChanged = true;
                }
                headingSourceInitialized = true;
                headingSourceSynthetic = synthetic;
                headingSourceMock = isMockLocation(location);
                headingChanged |= travelHeading.update(new GeoPoint(location.getLatitude(), location.getLongitude()),
                        sampleMillis, location.getAccuracy(), location.hasBearing() ? location.getBearing() : Double.NaN,
                        location.hasSpeed() ? location.getSpeed() : Double.NaN,
                        location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : Double.NaN);
            }
        }
        scheduleOnlineRefresh();
        // Preserve the previous 2 m real-GPS jitter filter; only test providers
        // need stationary heading updates. The companion supplies both providers,
        // so ignore identical motion fixes rather than redraw twice.
        if (lastLocation != null) {
            boolean movingNow = location.hasSpeed() && location.getSpeed() > 1.4;
            boolean movingBefore = lastLocation.hasSpeed() && lastLocation.getSpeed() > 1.4;
            if (!isMockLocation(location) && !isMockLocation(lastLocation) && movingNow == movingBefore
                    && location.distanceTo(lastLocation) < 2) {
                // Keep orientation independent of the existing position jitter filter.
                if (headingChanged) { updatePositionPuck(); noteFrameActivity(); }
                renewRoadObservation(); refreshSpeedTile(); return;
            }
            if (isMockLocation(location) && isMockLocation(lastLocation)
                    && location.getLatitude() == lastLocation.getLatitude()
                    && location.getLongitude() == lastLocation.getLongitude()
                    && location.hasBearing() == lastLocation.hasBearing() && location.getBearing() == lastLocation.getBearing()
                    && location.hasSpeed() == lastLocation.hasSpeed() && location.getSpeed() == lastLocation.getSpeed()) {
                if (headingChanged) { updatePositionPuck(); noteFrameActivity(); }
                renewRoadObservation(); refreshSpeedTile(); return;
            }
        }
        frameCounterSession.locationAccepted(SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos());
        lastLocation = location;
        cameraHeading.update(location.hasBearing(), location.getBearing(),
                location.hasSpeed(), location.getSpeed());
        position = new GeoPoint(location.getLatitude(), location.getLongitude());
        framePolicy.onLocation(location.hasSpeed() && Float.isFinite(location.getSpeed()) && location.getSpeed() > 1.4, SystemClock.uptimeMillis());
        applyFrameDecision();
        boolean moving = !synthetic && location.hasSpeed() && location.getSpeed() > 1.4;
        query.setEnabled(!moving);
        search.setEnabled(!moving);
        if (moving) hideSearchKeyboard();
        if (route != null && tracker != null) {
            RouteTracker.Progress progress = tracker.update(position, location.getAccuracy(), location.getElapsedRealtimeNanos() / 1000000,
                    movingBearing(location));
            if (progress == null) return;
            updateGuidance(progress, synthetic);
        } else { refreshSpeedTile(); status(synthetic ? "REPLAY location" : "GPS ready · search for a destination"); }
        updateMap(false);
    }

    private void updateGuidance(RouteTracker.Progress progress, boolean synthetic) {
        updateGuidance(progress, synthetic, true);
    }

    private void updateGuidance(RouteTracker.Progress progress, boolean synthetic, boolean observation) {
        lastProgress = progress;
        renewRoadObservation();
        refreshSpeedTile();
        RoutePlan.Maneuver maneuver = route.maneuvers.get(progress.maneuverIndex);
        maneuverIcon.setManeuver(maneuver.type, progress.arrived);
        instruction.setText(progress.arrived ? "Arrived at " + route.destinationName
                : (progress.nextManeuverMeters > 25 ? formatDistance(progress.nextManeuverMeters) + " · " : "") + maneuver.instruction);
        if (!progress.usableFix) status("GPS accuracy is too low for turn guidance");
        else if (progress.offRoute) {
            status((synthetic ? "REPLAY · " : "") + "Off route; calculating a new route…");
            if (observation && !routeInFlight && SystemClock.elapsedRealtime() - lastRerouteMillis > 20000) requestRoute(true);
        } else {
            showTripSummary(progress, synthetic);
            if (!observation) return;
            if (!progress.arrived) announce(progress, maneuver);
            else if (spokenManeuver != Integer.MAX_VALUE) {
                speak("You have arrived at " + route.destinationName, 0);
                spokenManeuver = Integer.MAX_VALUE;
            }
        }
    }

    private void showTripSummary(RouteTracker.Progress progress, boolean synthetic) {
        String duration = TripSummary.duration(progress.remainingSeconds);
        String text = formatDistance(progress.remainingMeters);
        if (duration != null && progress.usableFix && !progress.offRoute && freshRoadObservation()) {
            long arrival = TripSummary.arrivalMillis(System.currentTimeMillis(), progress.remainingSeconds);
            if (arrival >= 0) text = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(arrival))
                    + " · " + duration + " · " + text;
        }
        status((synthetic ? "REPLAY · " : "") + text);
        showingTripSummary = true;
        main.removeCallbacks(refreshTripClock);
        if (started && !destroyed) main.postDelayed(refreshTripClock, 60000);
    }

    private void refreshTripClock() {
        if (started && !destroyed && showingTripSummary && lastProgress != null && route != null)
            showTripSummary(lastProgress, replay);
    }

    private void refreshSpeedTile() {
        main.removeCallbacks(expireSpeed);
        if (speedTile == null || destroyed) return;
        boolean matched = route != null && lastProgress != null && lastProgress.matched && !routeInFlight && freshRoadObservation();
        speedTile.show(SpeedReadout.create(speedObservation, SpeedDisplayPreferences.effective(getContext()),
                SystemClock.elapsedRealtime(), matched, matched ? lastProgress.speedLimitKph : Double.NaN));
        if (showingTripSummary && lastProgress != null && route != null) showTripSummary(lastProgress, replay);
        if (started && speedObservation != null) {
            long remaining = speedObservation.elapsedMillis + SpeedReadout.FRESH_MILLIS + 1 - SystemClock.elapsedRealtime();
            if (remaining > 0 && remaining <= SpeedReadout.FRESH_MILLIS + 1) main.postDelayed(expireSpeed, remaining);
        }
    }

    private boolean freshRoadObservation() {
        long now = SystemClock.elapsedRealtime();
        return roadObservation != null && roadObservation == speedObservation && now >= roadObservation.elapsedMillis
                && now - roadObservation.elapsedMillis <= SpeedReadout.FRESH_MILLIS;
    }

    /** Content-free advisory state for a normal service dump; no destinations or coordinates. */
    public String diagnosticFlags() {
        long age = speedObservation == null ? -1 : Math.max(0, SystemClock.elapsedRealtime() - speedObservation.elapsedMillis);
        return "nav_route=" + (route != null) + " nav_metadata=" + (route != null && route.metadataEnriched)
                + " nav_offline=" + (route != null && route.offline)
                + " nav_matching=" + (lastProgress != null && lastProgress.matched)
                + " nav_limit_known=" + (lastProgress != null && !Double.isNaN(lastProgress.speedLimitKph))
                + " nav_road_fresh=" + freshRoadObservation() + " nav_route_pending=" + routeInFlight
                + " nav_fix_age_ms=" + age + " " + (routeProviders == null ? providers : routeProviders).diagnosticFlags();
    }

    /** Reuse a confident match only for genuinely unchanged stationary/mocked observations.
     * A moving real fix with a new bearing must be rematched instead of renewing an old limit. */
    private void renewRoadObservation() {
        roadObservation = null;
        if (lastProgress == null || !lastProgress.matched || lastLocation == null || speedLocation == null
                || speedObservation == null || !speedLocation.hasAccuracy() || !Float.isFinite(speedLocation.getAccuracy())
                || speedLocation.getAccuracy() < 0 || speedLocation.getAccuracy() > 20) return;
        boolean sameFix = speedLocation.getElapsedRealtimeNanos() == lastLocation.getElapsedRealtimeNanos();
        boolean stationary = (!speedLocation.hasSpeed() || speedLocation.getSpeed() <= 1.4)
                && (!lastLocation.hasSpeed() || lastLocation.getSpeed() <= 1.4);
        boolean sameMockMotion = isMockLocation(speedLocation) && isMockLocation(lastLocation)
                && speedLocation.getLatitude() == lastLocation.getLatitude() && speedLocation.getLongitude() == lastLocation.getLongitude()
                && speedLocation.hasBearing() == lastLocation.hasBearing() && speedLocation.getBearing() == lastLocation.getBearing()
                && speedLocation.hasSpeed() == lastLocation.hasSpeed() && speedLocation.getSpeed() == lastLocation.getSpeed();
        if (sameFix || ((stationary || sameMockMotion) && speedLocation.distanceTo(lastLocation) < 2)) roadObservation = speedObservation;
    }

    @SuppressWarnings("deprecation")
    private static boolean isMockLocation(Location location) {
        return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
    }

    private static Float movingBearing(Location location) {
        return location != null && location.hasSpeed() && Float.isFinite(location.getSpeed()) && location.getSpeed() > 1.4
                && location.hasBearing() && Float.isFinite(location.getBearing()) && location.getBearing() >= 0 && location.getBearing() < 360
                ? location.getBearing() : null;
    }

    private void requestRoute(boolean rerouting) {
        if (position == null || destination == null || !started) return;
        noteFrameActivity();
        cancelPending();
        invalidateOnlineHandoff();
        routeInFlight = true;
        roadObservation = null;
        refreshSpeedTile();
        lastRerouteMillis = SystemClock.elapsedRealtime();
        long token = generation;
        GeoPoint origin = position;
        NavigationProviders.Place target = destination;
        Float bearing = movingBearing(lastLocation);
        NavigationProviders network = providers;
        boolean online = internet.isOnline();
        long onlineRevision = onlineRefresh.connectionRevision();
        status(rerouting ? "Recalculating route…" : "Calculating route…");
        pending = worker.submit(() -> {
            try {
                RouteAttempt attempt = planRoute(network, origin, target, bearing, online);
                RoutePlan planned = attempt.route;
                main.post(() -> {
                    if (!current(token)) return;
                    routeInFlight = false;
                    route = planned;
                    routeDestination = target;
                    routeProviders = planned.offline ? null : network;
                    if (planned.offline && attempt.triedOnline && onlineRevision == onlineRefresh.connectionRevision())
                        onlineRefresh.suppressCurrentConnection(routeRevision);
                    lastProgress = null;
                    noteFrameActivity();
                    instruction.setVisibility(VISIBLE);
                    maneuverIcon.setVisibility(VISIBLE);
                    // Show guidance immediately while parked. Duplicate stationary locations are
                    // intentionally filtered, so waiting for receiveLocation can leave this blank.
                    RoutePlan.Maneuver initial = planned.maneuvers.get(0);
                    instruction.setText(initial.instruction);
                    maneuverIcon.setManeuver(initial.type, false);
                    stopRoute.setVisibility(VISIBLE);
                    tracker = new RouteTracker(planned);
                    spokenManeuver = -1; spokenBand = -1;
                    following = true;
                    String duration = TripSummary.duration(planned.seconds);
                    status(formatDistance(planned.lengthMeters()) + (duration == null ? "" : " · " + duration));
                    updateMap(true);
                    speak(rerouting ? "Route updated" : "Starting route to " + planned.destinationName, planned.lengthMeters());
                    if (lastLocation != null) {
                        // This initializes a fresh tracker from an existing accepted fix; it is
                        // not a fabricated GPS update and does not increment location counters.
                        RouteTracker.Progress initialProgress = tracker.update(position, lastLocation.getAccuracy(),
                                lastLocation.getElapsedRealtimeNanos() / 1000000, movingBearing(lastLocation));
                        if (initialProgress != null) updateGuidance(initialProgress, replay);
                    }
                    scheduleOnlineRefresh();
                });
                // Metadata never delays the first route or owns a newer destination/session.
                if (planned.offline) return;
                RoutePlan enriched = network.enrichRoute(planned);
                if (enriched != planned) main.post(() -> {
                    if (!current(token) || route != planned) return;
                    applyRouteMetadata(enriched, replay);
                });
            } catch (Exception error) { reportFailure(token, "Route unavailable", error); }
        });
    }

    private static final class RouteAttempt {
        final RoutePlan route;
        final boolean triedOnline;
        RouteAttempt(RoutePlan route, boolean triedOnline) { this.route = route; this.triedOnline = triedOnline; }
    }

    private RouteAttempt planRoute(NavigationProviders network, GeoPoint origin, NavigationProviders.Place target,
                                   Float bearing, boolean online) throws Exception {
        Exception onlineFailure = null;
        if (online) {
            try { return new RouteAttempt(network.route(origin, target, bearing), true); }
            catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception unavailable) { onlineFailure = unavailable; }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try { return new RouteAttempt(localNavigation.route(origin, target, bearing), online); }
        catch (java.io.IOException unavailable) {
            if (onlineFailure != null) throw onlineFailure;
            throw unavailable;
        }
    }

    private List<NavigationProviders.Place> searchPlaces(NavigationProviders network, String text, GeoPoint bias,
                                                        boolean online) throws Exception {
        Exception onlineFailure = null;
        if (online) {
            try {
                List<NavigationProviders.Place> found = network.search(text, bias);
                if (!found.isEmpty()) return found;
            } catch (InterruptedException cancelled) { throw cancelled; }
            catch (Exception unavailable) { onlineFailure = unavailable; }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try { return OfflineNavigationAccess.search(getContext(), text, bias, 10); }
        catch (java.io.IOException unavailable) {
            if (onlineFailure != null) throw onlineFailure;
            if (online) return List.of();
            throw unavailable;
        }
    }

    private void internetChanged(boolean online) {
        onlineRefresh.setOnline(online, SystemClock.elapsedRealtime());
        if (!online) cancelOnlineHandoff();
        else scheduleOnlineRefresh();
    }

    private void invalidateOnlineHandoff() {
        routeRevision++;
        cancelOnlineHandoff();
    }
    private void cancelOnlineHandoff() {
        main.removeCallbacks(refreshOnlineRoute);
        if (handoffPending != null) handoffPending.cancel(true);
        handoffPending = null;
        NavigationProviders closing = handoffProviders; handoffProviders = null;
        if (closing != null) closing.cancel();
    }

    private void scheduleOnlineRefresh() {
        main.removeCallbacks(refreshOnlineRoute);
        if (!started || destroyed || replay || routeInFlight || route == null || !route.offline
                || destination == null || routeDestination != destination || handoffPending != null) return;
        long delay = onlineRefresh.delayMillis(routeRevision, SystemClock.elapsedRealtime());
        if (delay >= 0) main.postDelayed(refreshOnlineRoute, delay);
    }

    /** Uses the newest accepted fix, independently of the map's two-meter jitter filter. */
    private Location freshRouteFix() {
        Location fix = speedLocation;
        if (fix == null || !fix.hasAccuracy() || !Float.isFinite(fix.getAccuracy())
                || fix.getAccuracy() < 0 || fix.getAccuracy() > 50) return null;
        long age = SystemClock.elapsedRealtime() - fix.getElapsedRealtimeNanos() / 1000000;
        return age >= 0 && age <= 15000 ? new Location(fix) : null;
    }

    private void refreshOnlineRoute() {
        if (!started || destroyed || replay || routeInFlight || route == null || !route.offline
                || destination == null || routeDestination != destination || handoffPending != null) return;
        Location originFix = freshRouteFix();
        if (originFix == null) return; // A later GPS callback can schedule the one eligible refresh.
        OnlineRouteRefreshPolicy.Ticket ticket = onlineRefresh.begin(routeRevision, SystemClock.elapsedRealtime());
        if (ticket == null) return;
        RoutePlan offlineRoute = route;
        NavigationProviders.Place target = destination;
        NavigationProviders network = new NavigationProviders(config);
        handoffProviders = network;
        handoffPending = handoffWorker.submit(() -> {
            try {
                RoutePlan planned = network.route(new GeoPoint(originFix.getLatitude(), originFix.getLongitude()),
                        target, movingBearing(originFix));
                main.post(() -> {
                    if (!handoffCurrent(ticket, target, offlineRoute, network)) return;
                    Location latest = freshRouteFix();
                    if (latest == null) return;
                    RouteTracker nextTracker = new RouteTracker(planned);
                    RouteTracker.Progress progress = nextTracker.update(new GeoPoint(latest.getLatitude(), latest.getLongitude()),
                            latest.getAccuracy(), latest.getElapsedRealtimeNanos() / 1000000, movingBearing(latest));
                    if (progress == null || !progress.usableFix || progress.offRoute || !progress.matched) return;
                    route = planned; tracker = nextTracker; routeProviders = network; lastProgress = null;
                    spokenManeuver = -1; spokenBand = -1;
                    updateGuidance(progress, false);
                    updateMap(true);
                });
                RoutePlan enriched = network.enrichRoute(planned);
                if (enriched != planned) main.post(() -> {
                    if (!handoffCurrent(ticket, target, planned, network)) return;
                    applyRouteMetadata(enriched, false);
                });
            } catch (Exception unavailable) {
                // Keep the current local route; a new validated connection can try again.
            } finally {
                main.post(() -> {
                    if (handoffProviders == network) { handoffProviders = null; handoffPending = null; }
                });
            }
        });
    }

    private boolean handoffCurrent(OnlineRouteRefreshPolicy.Ticket ticket, NavigationProviders.Place target,
                                   RoutePlan expected, NavigationProviders network) {
        return started && !destroyed && !replay && !routeInFlight && onlineRefresh.current(ticket, routeRevision)
                && destination == target && routeDestination == target && route == expected && handoffProviders == network;
    }

    private void applyRouteMetadata(RoutePlan enriched, boolean synthetic) {
        // Replay owns a fixed route identity until it stops. Optional live metadata
        // must not invalidate that identity and strand replay's location ownership.
        if (replay || tracker == null || route == null) return;
        try {
            RouteTracker.Progress progress = tracker.updatePlan(enriched);
            route = enriched;
            if (progress != null) updateGuidance(progress, synthetic, false);
        } catch (IllegalArgumentException incompatible) {
            // Optional metadata never replaces a different route or resets progress.
        }
    }

    private void announce(RouteTracker.Progress progress, RoutePlan.Maneuver maneuver) {
        int band = progress.nextManeuverMeters <= 40 ? 2 : progress.nextManeuverMeters <= 150 ? 1
                : progress.nextManeuverMeters <= 600 ? 0 : -1;
        if (band < 0) return;
        if (progress.maneuverIndex != spokenManeuver || band > spokenBand) {
            spokenManeuver = progress.maneuverIndex;
            spokenBand = band;
            String text = (progress.nextManeuverMeters > 40 ? "In " + spokenDistance(progress.nextManeuverMeters) + ", " : "")
                    + maneuver.spokenInstruction;
            speak(text, progress.nextManeuverMeters);
        }
    }

    private void speak(String text, double distance) {
        listener.onNavigationInstruction(text, distance);
        if (config.speakInstructions && !speechSuppressed && started) { speech.speak(text); announcementSpeech = false; }
    }

    private void cancelSpeech() { speech.cancel(); }

    /** Explicit Read action only; GPS/map rendering need not be running. */
    public void readAloud(String text) { if (!destroyed && !speechSuppressed) { speech.speak(text); announcementSpeech = true; } }
    public boolean isReadingAnnouncement() { return announcementSpeech; }
    /** Cancels only the explicit message Read owner, preserving later route guidance. */
    public void cancelReadAloud() { if (announcementSpeech) { announcementSpeech = false; speech.cancel(); } }
    public void cancelSpokenOutput() { speech.cancel(); }

    /** One-tap Saved/Recent picker. Merely opening it never geocodes or starts a route. */
    public void showDestinations(boolean recent) {
        cancelPlaceInteraction();
        hideSearchKeyboard();
        if (destroyed) return;
        results.removeAllViews();
        addPickerHeader(recent ? "Recent destinations" : "Saved places", true);
        DestinationStore.Snapshot snapshot = destinations.snapshot();
        List<DestinationStore.Place> places = recent ? snapshot.recent : snapshot.saved;
        if (!recent) {
            for (DestinationStore.Shortcut slot : new DestinationStore.Shortcut[] {DestinationStore.Shortcut.HOME, DestinationStore.Shortcut.WORK}) {
                DestinationStore.Place place = snapshot.shortcut(slot);
                if (place != null) addPlaceRow(new NavigationProviders.Place((slot == DestinationStore.Shortcut.HOME ? "Home · " : "Work · ") + place.name,
                    new GeoPoint(place.latitude, place.longitude)), false);
            }
        }
        for (DestinationStore.Place place : places) {
            if (!recent && place.shortcut != DestinationStore.Shortcut.NONE) continue;
            addPlaceRow(new NavigationProviders.Place(place.name, new GeoPoint(place.latitude, place.longitude)), recent);
        }
        if (places.isEmpty()) results.addView(label(recent ? "Choose a search result to build your recent list." : "Save a search result, Home or Work to find it here.", 15));
        resultScroller.setVisibility(VISIBLE);
        refreshBanner();
        status(recent ? "Choose a recent destination" : "Choose a saved destination");
    }
    private void showSearchKeyboard() {
        if (!started || destroyed || !query.isEnabled()) return;
        cancelPlaceInteraction();
        resultScroller.setVisibility(GONE);
        if (keyboard == null) {
            keyboard = new CarTextKeyboard(getContext(), query, new CarTextKeyboard.Listener() {
                @Override public void onFind() { search(query.getText().toString()); }
                @Override public void onClose() { hideSearchKeyboard(); }
            });
            keyboard.setFloatingControlClearance(76, 80);
            // Four 48dp touch rows fit below search, leaving the map credits unobscured.
            LayoutParams parameters = new LayoutParams(LayoutParams.MATCH_PARENT, keyboardHeight(), Gravity.BOTTOM);
            parameters.bottomMargin = dp(16); parameters.leftMargin = dp(4); parameters.rightMargin = dp(4);
            addView(keyboard, parameters);
        }
        keyboard.setVisibility(VISIBLE);
        keyboard.setElevation(dp(8)); keyboard.bringToFront();
        refreshBanner();
        query.requestFocus();
    }
    private void hideSearchKeyboard() { if (keyboard != null) keyboard.setVisibility(GONE); refreshBanner(); }
    private int keyboardHeight() { return Math.max(dp(192), Math.min(dp(256), getHeight() - dp(72))); }

    private void addPickerHeader(String title, boolean manage) {
        LinearLayout row = new LinearLayout(getContext());
        TextView heading = label(title, 16);
        heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(12), 0, 0, 0);
        row.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
        if (manage) row.addView(button("Edit on phone", () -> {
            try { DestinationManagerActivity.open(getContext()); status("Edit destinations on the phone"); }
            catch (RuntimeException error) { status("Open Saved places from phone setup"); }
        }), new LinearLayout.LayoutParams(dp(120), dp(48)));
        row.addView(button("Close", this::cancelCommandFlow), new LinearLayout.LayoutParams(dp(64), dp(48)));
        results.addView(row);
    }
    private void addPlaceRow(NavigationProviders.Place place, boolean save) {
        addPlaceRow(place, save, null);
    }
    private void addPlaceRow(NavigationProviders.Place place, boolean save, NavigationSearchGate.Candidates candidates) {
        LinearLayout row = new LinearLayout(getContext());
        Button choice = button(place.name, () -> {
            if (!started || destroyed || renderingSuspended || (candidates != null && !placeSearches.current(candidates))) return;
            if (candidates != null && candidates.requireConfirmation) confirmDestination(place, candidates);
            else navigateTo(place);
        });
        choice.setAllCaps(false); choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); choice.setTextSize(17);
        choice.setPadding(dp(12), 0, dp(8), 0); choice.setMaxLines(2); choice.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(choice, new LinearLayout.LayoutParams(0, dp(60), 1));
        if (save) row.addView(button("Save", () -> editDestination(place)), new LinearLayout.LayoutParams(dp(64), dp(60)));
        results.addView(row);
    }
    private void confirmDestination(NavigationProviders.Place place, NavigationSearchGate.Candidates candidates) {
        NavigationSearchGate.Confirmation selected = placeSearches.confirm(candidates, place.name, place.point.latitude, place.point.longitude);
        if (selected == null) return;
        results.removeAllViews(); addPickerHeader("Confirm destination", false);
        TextView name = label(selected.name, 21);
        name.setPadding(dp(12), dp(12), dp(12), dp(12)); name.setMaxLines(3); name.setEllipsize(TextUtils.TruncateAt.END);
        results.addView(name);
        TextView notice = label("", 14); notice.setPadding(dp(12), 0, dp(12), dp(8)); notice.setVisibility(GONE); results.addView(notice);
        LinearLayout actions = new LinearLayout(getContext());
        actions.addView(button("Cancel", this::cancelCommandFlow), new LinearLayout.LayoutParams(0, dp(52), 1));
        Button start = button("Start route", () -> {
            if (!started || destroyed || renderingSuspended) return;
            if (position == null) {
                notice.setText("Wait for a GPS fix, then choose Start route again"); notice.setVisibility(VISIBLE);
                status("Wait for a GPS fix, then choose Start route again"); return;
            }
            if (!placeSearches.consume(selected)) return;
            reportCancelableFlow();
            navigateTo(new NavigationProviders.Place(selected.name, new GeoPoint(selected.latitude, selected.longitude)));
        });
        CarUi.applyButton(start, true); start.setSingleLine(true);
        actions.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1)); results.addView(actions);
        resultScroller.scrollTo(0, 0); resultScroller.setVisibility(VISIBLE); refreshBanner();
        status("Review the destination, then choose Start route");
    }
    private void editDestination(NavigationProviders.Place place) {
        cancelCommandFlow();
        try {
            String name = place.name.length() > 200 ? place.name.substring(0, 200) : place.name;
            DestinationManagerActivity.save(getContext(), name, place.point.latitude, place.point.longitude);
            status("Name or edit this saved place on the phone");
        } catch (RuntimeException error) { status("Open Saved places from phone setup"); }
    }

    private void loadStyle() {
        if (map == null || destroyed) return;
        noteFrameActivity();
        fullyRenderedMapFrame = false;
        map.setStyle(config.styleUrl, loaded -> {
            if (destroyed) return;
            noteFrameActivity();
            style = loaded;
            // Flat car map: omit 3D building extrusion work and retain road/place labels.
            for (org.maplibre.android.style.layers.Layer layer : loaded.getLayers()) {
                if (layer instanceof FillExtrusionLayer) layer.setProperties(PropertyFactory.visibility(Property.NONE));
            }
            loaded.addSource(new GeoJsonSource(ROUTE_SOURCE, FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            loaded.addLayer(new LineLayer(ROUTE_SOURCE, ROUTE_SOURCE).withProperties(
                    PropertyFactory.lineColor("#168dff"), PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)));
            loaded.addSource(new GeoJsonSource(DESTINATION_SOURCE, FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            loaded.addLayer(new CircleLayer(DESTINATION_SOURCE, DESTINATION_SOURCE).withProperties(
                    PropertyFactory.circleColor("#ffb347"), PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(2f)));
            loaded.addSource(new GeoJsonSource(POSITION_SOURCE, FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0])));
            positionPuck.addToStyle(loaded, POSITION_SOURCE);
            updateMap(true);
        });
    }

    private void updateMap(boolean routeChanged) {
        if (renderingSuspended || map == null || style == null || !style.isFullyLoaded()) return;
        if (routeChanged) {
            GeoJsonSource routeSource = style.getSourceAs(ROUTE_SOURCE);
            if (route != null) {
                List<Point> points = new ArrayList<>();
                for (GeoPoint item : route.points) points.add(Point.fromLngLat(item.longitude, item.latitude));
                routeSource.setGeoJson(LineString.fromLngLats(points));
            } else routeSource.setGeoJson(FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0]));
            GeoJsonSource target = style.getSourceAs(DESTINATION_SOURCE);
            if (destination != null) target.setGeoJson(Point.fromLngLat(destination.point.longitude, destination.point.latitude));
            else target.setGeoJson(FeatureCollection.fromFeatures(new org.maplibre.geojson.Feature[0]));
        }
        if (position != null) {
            updatePositionPuck();
            if (following) {
                CameraPosition desired = new CameraPosition.Builder()
                        .target(new LatLng(position.latitude, position.longitude)).zoom(route == null ? 14 : 16)
                        .bearing(cameraHeading.bearing()).tilt(0).build();
                // Compare actual camera state, never a cached target that could hide a gesture.
                if (!desired.equals(map.getCameraPosition())) map.setCameraPosition(desired);
            }
        }
    }

    private void updatePositionPuck() {
        if (renderingSuspended || map == null || style == null || !style.isFullyLoaded() || position == null) return;
        GeoJsonSource current = style.getSourceAs(POSITION_SOURCE);
        if (current != null) current.setGeoJson(positionPuck.feature(position, travelHeading));
    }

    private void cancelPending() {
        generation++;
        routeInFlight = false;
        if (pending != null) pending.cancel(true);
        providers.cancel();
        localNavigation.cancel();
        pending = null;
    }

    private boolean current(long token) { return token == generation && started && !destroyed; }

    private void reportFailure(long token, String message, Exception error) {
        if (error instanceof InterruptedException || Thread.currentThread().isInterrupted()) return;
        main.post(() -> {
            if (!current(token)) return;
            routeInFlight = false;
            status(message + ": " + (error.getMessage() == null ? "check provider connection" : error.getMessage()));
        });
    }

    private void status(String text) {
        showingTripSummary = false;
        main.removeCallbacks(refreshTripClock);
        if (text.equals(lastStatus)) return;
        lastStatus = text;
        detail.setText(text);
        refreshBanner();
        listener.onStatus(text);
    }

    private void refreshBanner() {
        if (banner == null || resultScroller == null) return;
        boolean overlay = resultScroller.getVisibility() == VISIBLE || (keyboard != null && keyboard.getVisibility() == VISIBLE);
        boolean idle = lastStatus.isBlank() || lastStatus.startsWith("GPS ready") || lastStatus.startsWith("Choose ")
                || lastStatus.equals("Navigation stopped") || lastStatus.equals("Place selection cancelled") || lastStatus.equals("REPLAY location");
        banner.setVisibility(!overlay && (route != null || routeInFlight || !idle) ? VISIBLE : GONE);
    }

    private Button button(String text, Runnable action) {
        Button result = CarUi.button(getContext(), text, false);
        result.setTextSize(13);
        result.setPadding(dp(4), 0, dp(4), 0);
        result.setOnClickListener(view -> { noteFrameActivity(); action.run(); });
        return result;
    }

    private TextView label(String text, float size) {
        return CarUi.text(getContext(), text, size, CarUi.TEXT);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String formatDistance(double meters) {
        return meters >= 1000 ? String.format(Locale.US, "%.1f km", meters / 1000)
                : Math.round(meters / 10) * 10 + " m";
    }

    private static String spokenDistance(double meters) {
        return meters >= 1000 ? String.format(Locale.US, "%.1f kilometers", meters / 1000)
                : Math.round(meters / 10) * 10 + " meters";
    }

    private static GeoPoint interpolate(RoutePlan route, double distance) {
        int index = 1;
        while (index < route.points.size() - 1 && route.cumulativeMeters[index] < distance) index++;
        GeoPoint a = route.points.get(index - 1), b = route.points.get(index);
        double length = route.cumulativeMeters[index] - route.cumulativeMeters[index - 1];
        double fraction = length == 0 ? 0 : (distance - route.cumulativeMeters[index - 1]) / length;
        return new GeoPoint(a.latitude + (b.latitude - a.latitude) * fraction,
                a.longitude + (b.longitude - a.longitude) * fraction);
    }
}
