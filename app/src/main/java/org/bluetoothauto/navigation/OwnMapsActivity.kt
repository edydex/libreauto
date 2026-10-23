// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.bluetoothauto.BluetoothAutoService
import org.bluetoothauto.Library
import org.bluetoothauto.model.Position
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** Phone navigation controls and map. A background trip belongs to the foreground media session. */
class OwnMapsActivity : Activity(), LocationListener {
    private val background = Color.rgb(16, 28, 35)
    private val card = Color.rgb(25, 42, 51)
    private val ink = Color.rgb(237, 243, 244)
    private val muted = Color.rgb(167, 183, 189)
    private val accent = Color.rgb(99, 217, 194)
    private val main = Handler(Looper.getMainLooper())
    private val searchWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "own-map-search").apply { isDaemon = true } }
    private val searchProvider = OwnMapProvider()
    private var searchJob: Future<*>? = null
    private var searchEpoch = 0L
    private var visible = false
    private var following = true
    private var centered = false
    private var renderedRoute: RoutePlan? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private lateinit var mapView: MapView
    private lateinit var query: EditText
    private lateinit var results: LinearLayout
    private lateinit var resultScroll: ScrollView
    private lateinit var status: TextView
    private lateinit var instruction: TextView
    private lateinit var summary: TextView
    private lateinit var stop: Button
    private lateinit var locations: LocationManager
    private val observer: (OwnNavigation.Snapshot) -> Unit = { if (visible) render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background; window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0
        OwnNavigation.initialize(this)
        locations = getSystemService(LocationManager::class.java)
        MapLibre.getInstance(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(this@OwnMapsActivity.background); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        setContentView(root)
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(button("‹ Back") { finish() }, LinearLayout.LayoutParams(dp(84), dp(46)))
        heading.addView(label("Own maps", 23, true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        root.addView(heading)
        val search = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        query = EditText(this).apply {
            setSingleLine(true); hint = "Search place or address"; setTextColor(ink); setHintTextColor(muted)
            textSize = 16f; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false }
            setText(savedInstanceState?.getString("query", "") ?: "")
        }
        search.addView(query, LinearLayout.LayoutParams(0, dp(52), 1f))
        search.addView(button("Search", true) { search() }, LinearLayout.LayoutParams(dp(88), dp(46)))
        root.addView(search)
        val shortcuts = LinearLayout(this)
        listOf("Saved" to { showPlaces(true) }, "Recent" to { showPlaces(false) }, "Center" to {
            following = true; OwnNavigation.snapshot.position?.let { center(it, true) }; Unit
        }).forEach { (title, action) -> shortcuts.addView(button(title, false, action),
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        root.addView(shortcuts)
        status = label("Search and directions need internet. Hold the map to add a pin.", 12, false, muted)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(6), dp(3), dp(6)) })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultScroll = ScrollView(this).apply { addView(results); visibility = View.GONE }
        root.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(170)))
        mapView = MapView(this, MapLibreMapOptions.createFromAttributes(this).textureMode(true)).apply { onCreate(savedInstanceState) }
        root.addView(mapView, LinearLayout.LayoutParams(-1, 0, 1f))
        val guidance = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)); background = round(card)
        }
        instruction = label("Choose a destination", 19, true).apply { maxLines = 3 }
        summary = label("Directions stay on your stereo when you return to music.", 13, false, muted)
        guidance.addView(instruction); guidance.addView(summary)
        stop = button("Stop directions") { OwnNavigation.stop() }.apply { visibility = View.GONE }
        guidance.addView(stop, LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(guidance, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        mapView.getMapAsync { loaded ->
            if (isDestroyed) return@getMapAsync
            map = loaded
            loaded.uiSettings.isCompassEnabled = true
            loaded.addOnCameraMoveStartedListener { reason -> if (reason == 1) following = false }
            loaded.addOnMapLongClickListener { point ->
                choose(OwnMapProvider.Place("Dropped pin", GeoPoint(point.latitude, point.longitude)), false); true
            }
            loaded.setStyle(OwnMapProvider.STYLE) { ready ->
                if (isDestroyed) return@setStyle
                style = ready
                ready.layers.filterIsInstance<FillExtrusionLayer>().forEach { it.setProperties(visibility(Property.NONE)) }
                ready.addSource(GeoJsonSource("own-route", empty()))
                ready.addLayer(LineLayer("own-route", "own-route").withProperties(
                    lineColor("#007e79"), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
                ready.addSource(GeoJsonSource("own-destination", empty()))
                ready.addLayer(CircleLayer("own-destination", "own-destination").withProperties(
                    circleColor("#EBAF59"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                ready.addSource(GeoJsonSource("own-position", empty()))
                ready.addLayer(CircleLayer("own-position", "own-position").withProperties(
                    circleColor("#007e79"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
                renderedRoute = null; render(OwnNavigation.snapshot)
            }
        }
    }

    override fun onStart() { super.onStart(); visible = true; mapView.onStart(); OwnNavigation.observers.add(observer); listen(); render(OwnNavigation.snapshot) }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() {
        visible = false; OwnNavigation.observers.remove(observer); locations.removeUpdates(this)
        searchEpoch++; searchJob?.cancel(true); searchProvider.cancel(); mapView.onStop(); super.onStop()
    }
    override fun onDestroy() { searchWorker.shutdownNow(); mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("query", query.text.toString()); mapView.onSaveInstanceState(out); super.onSaveInstanceState(out) }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 81); return
        }
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) if (locations.isProviderEnabled(provider)) {
                locations.getLastKnownLocation(provider)?.let(::onLocationChanged)
                locations.requestLocationUpdates(provider, 2000, 0f, this, Looper.getMainLooper())
            }
        } catch (_: SecurityException) { status.text = "Allow location to start directions." }
        catch (_: IllegalArgumentException) { status.text = "Turn on your phone’s location to start directions." }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 81 && visible) {
            if (grants.any { it == PackageManager.PERMISSION_GRANTED }) listen()
            else status.text = "Location is needed for directions. You can still search and save places."
        }
    }
    override fun onLocationChanged(location: Location) {
        val recorded = location.elapsedRealtimeNanos / 1_000_000
        val age = SystemClock.elapsedRealtime() - recorded
        if (age !in 0..30_000 || !location.hasAccuracy()) return
        OwnNavigation.updatePosition(this, Position(location.latitude, location.longitude,
            if (location.hasBearing() && location.hasSpeed() && location.speed >= 1.3f) location.bearing else null,
            location.accuracy, recorded))
    }
    override fun onProviderEnabled(provider: String) { if (visible) { locations.removeUpdates(this); listen() } }
    override fun onProviderDisabled(provider: String) { status.text = "Turn on your phone’s location to keep directions up to date." }
    @Deprecated("Legacy location callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun search() {
        val text = query.text.toString().trim()
        if (text.length !in 2..300) { status.text = "Enter a place or address."; return }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
        searchEpoch++; val token = searchEpoch
        searchJob?.cancel(true); searchProvider.cancel()
        status.text = "Searching…"; results.removeAllViews(); resultScroll.visibility = View.GONE
        val bias = OwnNavigation.snapshot.position?.let { GeoPoint(it.latitude, it.longitude) }
        searchJob = searchWorker.submit {
            try {
                val places = searchProvider.search(text, bias)
                main.post {
                    if (!visible || token != searchEpoch) return@post
                    status.text = if (places.isEmpty()) "No places found. Try the street address, or hold the map to add a pin." else "Choose a place"
                    showResults(places)
                }
            } catch (_: InterruptedException) { }
            catch (_: Exception) { main.post {
                if (visible && token == searchEpoch) status.text = "Search is unavailable. Check your internet connection and try again."
            } }
        }
    }
    private fun showResults(places: List<OwnMapProvider.Place>) {
        results.removeAllViews()
        places.forEach { place -> results.addView(button(place.name) { choose(place, false) }, LinearLayout.LayoutParams(-1, -2)) }
        if (places.isNotEmpty()) results.addView(button("Close results") { resultScroll.visibility = View.GONE })
        resultScroll.visibility = if (places.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun showPlaces(saved: Boolean) {
        val places = if (saved) OwnDestinations.saved(this) else OwnDestinations.recent(this)
        if (places.isEmpty()) {
            AlertDialog.Builder(this).setTitle(if (saved) "Saved places" else "Recent places")
                .setMessage(if (saved) "Search for a place, or hold the map to add a pin. Choose Save as… to give it a nickname." else "Places appear here after you start directions.")
                .setPositiveButton("OK", null).show(); return
        }
        AlertDialog.Builder(this).setTitle(if (saved) "Saved places" else "Recent places")
            .setItems(places.map { it.name }.toTypedArray()) { _, index -> choose(places[index], saved) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun choose(place: OwnMapProvider.Place, saved: Boolean) {
        val actions = if (saved) arrayOf("Start directions", "Rename saved place", "Remove saved place") else arrayOf("Start directions", "Save as…")
        AlertDialog.Builder(this).setTitle(place.name).setItems(actions) { _, index -> when (index) {
            0 -> {
                resultScroll.visibility = View.GONE; following = true
                startForegroundService(Intent(this, BluetoothAutoService::class.java).setAction(BluetoothAutoService.LIVE))
                OwnNavigation.start(this, place)
            }
            1 -> saveAs(place)
            2 -> { OwnDestinations.remove(this, place); Toast.makeText(this, "Saved place removed", Toast.LENGTH_SHORT).show() }
        } }.setNegativeButton("Cancel", null).show()
    }
    private fun saveAs(place: OwnMapProvider.Place) {
        val name = EditText(this).apply { setSingleLine(true); setText(place.name); selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(name) }
        val dialog = AlertDialog.Builder(this).setTitle("Place nickname").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                OwnDestinations.save(this, OwnMapProvider.Place(name.text.toString(), place.point)); dialog.dismiss()
                Toast.makeText(this, "Place saved", Toast.LENGTH_SHORT).show()
            } catch (error: IllegalArgumentException) { name.error = "Enter a nickname" }
            catch (error: IllegalStateException) { name.error = error.message }
        } }
        dialog.show()
    }
    private fun render(state: OwnNavigation.Snapshot) {
        instruction.text = state.guidance?.let {
            val distance = GuidanceFormatter.formatDistance(it.distanceMeters, it.distanceLabel, Library.units(this))
            if (distance.isBlank()) it.instruction else "$distance · ${it.instruction}"
        } ?: "Choose a destination"
        val route = state.route; val progress = state.progress
        summary.text = when {
            state.error != null -> state.error
            state.routing -> "Getting driving directions…"
            route != null && progress?.arrived == true -> "Arrived · ${route.destinationName}"
            route != null -> {
                val seconds = progress?.remainingSeconds?.takeIf { it.isFinite() } ?: route.seconds
                val meters = progress?.remainingMeters ?: route.lengthMeters()
                val distance = GuidanceFormatter.formatDistance(meters, "", Library.units(this))
                "${(seconds / 60).roundToInt().coerceAtLeast(1)} min · $distance · ${route.destinationName}"
            }
            else -> "Directions stay on your stereo when you return to music."
        }
        stop.visibility = if (route != null || state.routing) View.VISIBLE else View.GONE
        if (state.error != null) status.text = state.error
        if (style != null && (renderedRoute !== route || (route != null && source("own-route") == null))) {
            renderedRoute = route
            source("own-route")?.let { src -> if (route == null) src.setGeoJson(empty()) else
                src.setGeoJson(LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) })) }
            source("own-destination")?.let { src -> state.destination?.let { src.setGeoJson(Point.fromLngLat(it.point.longitude, it.point.latitude)) } ?: src.setGeoJson(empty()) }
        }
        state.position?.let { point ->
            source("own-position")?.setGeoJson(Point.fromLngLat(point.longitude, point.latitude))
            if (following || !centered) center(point, false)
        }
    }
    private fun center(point: Position, force: Boolean) {
        if (!force && !following && centered) return
        map?.cameraPosition = CameraPosition.Builder().target(LatLng(point.latitude, point.longitude))
            .zoom(if (OwnNavigation.active) 15.5 else 14.0).bearing(0.0).tilt(0.0).build()
        if (map != null) centered = true
    }
    private fun source(id: String): GeoJsonSource? = style?.getSourceAs(id)
    private fun empty() = FeatureCollection.fromFeatures(emptyArray<org.maplibre.geojson.Feature>())
    private fun label(text: String, size: Int, bold: Boolean = false, color: Int = ink) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f; setTextColor(if (primary) this@OwnMapsActivity.background else ink)
        background = round(if (primary) accent else card); minHeight = dp(44)
        setPadding(dp(10), dp(4), dp(10), dp(4)); setOnClickListener { action() }
    }
    private fun round(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(12).toFloat() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
}
