// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.*
import org.openandroidauto.ServiceState
import org.openandroidauto.navigation.DestinationPreferences
import org.openandroidauto.navigation.NavigationConfig
import org.openandroidauto.ui.CarUi
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** User-selected visual map packages only. No background worker, search index or routing engine. */
class OfflineMapsActivity : Activity() {
    companion object {
        private const val OWNER="OpenAutoLiteVisualArea/1"
        private const val VIEWPORT_OWNER="OpenAutoLiteVisualArea/2"
        private val DENSITIES=intArrayOf(1,2)
        private fun ready(status:OfflineRegionStatus?)=status!=null&&OfflineCompletion.ready(status.isRequiredResourceCountPrecise,
            status.requiredResourceCount,status.completedResourceCount,status.completedTileCount)
        fun open(activity:Activity) { activity.startActivity(Intent(activity,OfflineMapsActivity::class.java)) }
    }
    private enum class Page { LIST, EDIT, DOWNLOAD, PREVIEW }
    private class Area(val id:String,val name:String,val bounds:OfflineAreaPolicy.Bounds,val width:Int) {
        val regions=mutableMapOf<Int,OfflineRegion>()
        val statuses=mutableMapOf<Int,OfflineRegionStatus>()
        var navigationReady=false
        fun visualComplete()=DENSITIES.all { ready(statuses[it]) }
        fun complete()=visualComplete()&&navigationReady
    }
    private val main=Handler(Looper.getMainLooper())
    private val watchScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var projectionWatch:Job?=null
    private lateinit var manager:OfflineManager
    private lateinit var column:LinearLayout
    private var page=Page.EDIT
    private var activityStarted=false
    private var activityResumed=false
    private var generation=0L
    private var operation=0L
    private var deleting=false
    private var areas=emptyList<Area>()
    private val decodeFailures=linkedMapOf<String,Int>()
    private var normalizedNativeBounds=0
    private var activeArea:Area?=null
    private var activeRegion:OfflineRegion?=null
    private var navigationCancellation:CancellationSignal?=null
    private var navigationDownload:Job?=null
    private var transport:OfflineMapHttp?=null
    private var pendingClosures=0
    private var returnToPickerAfterCleanup=false
    private var progress:TextView?=null
    private var status:TextView?=null
    private var mapView:MapView?=null
    private var mapStarted=false
    private var mapResumed=false
    private var pickerMap:MapLibreMap?=null
    private var pickerZoom=13.5
    private var pickerMoving=false
    private var pickerLoaded=false
    private var pickerDownload:Button?=null
    private var pickerSummary:TextView?=null
    private val outlines=mutableListOf<Polygon>()
    private val outlineEdges=mutableListOf<Polyline>()
    private val redrawOutlines=Runnable { drawOutlines() }
    private var offlinePreview=false
    private var locationRequest:CancellationSignal?=null
    private var areaName=""
    private var latitude=0.0
    private var longitude=0.0
    private var coordinateText=""
    private var areaWidth=2
    private var coordinates:EditText?=null
    private var lastNotice=""

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        MapLibre.getInstance(applicationContext)
        org.maplibre.android.module.http.HttpRequestUtil.setLogEnabled(false)
        manager=OfflineManager.getInstance(this)
        areaName=state?.getString("name").orEmpty().take(60)
        latitude=state?.getDouble("latitude",latitude)?:latitude
        longitude=state?.getDouble("longitude",longitude)?:longitude
        areaWidth=state?.getInt("width",2)?.takeIf { it in OfflineAreaPolicy.WIDTH_KM }?:2
        coordinateText=state?.getString("coordinates").orEmpty().take(80)
        pickerZoom=state?.getDouble("zoom",pickerZoom)?:pickerZoom
        if(state==null) {
            DestinationPreferences.open(this).snapshot().saved.firstOrNull()?.let {
                latitude=it.latitude;longitude=it.longitude;areaName=it.name.take(60)
            }
        }
        showEditor()
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("name",areaName);out.putDouble("latitude",latitude);out.putDouble("longitude",longitude)
        out.putInt("width",areaWidth);out.putString("coordinates",coordinateText);out.putBoolean("editing",page==Page.EDIT)
        out.putDouble("zoom",pickerZoom)
        super.onSaveInstanceState(out)
    }
    override fun onStart() {
        super.onStart();activityStarted=true
        projectionWatch?.cancel()
        projectionWatch=watchScope.launch {
            launch { ServiceState.connectionState.collect { yield();reconcileProjection() } }
            launch { ServiceState.events.collect { yield();reconcileProjection() } }
        }
        if(page==Page.EDIT) { showEditor();return }
        if(page==Page.LIST) { showList(lastNotice);return }
        if(page==Page.DOWNLOAD&&activeArea==null&&!deleting) { showEditor();return }
        if(offlinePreview) {
            if(ServiceState.projectionActive) { showList("Offline preview closed because a car connection is active.");return }
            MapLibre.setConnected(false)
        }
        mapView?.let { it.onStart();mapStarted=true }
    }
    override fun onResume() { super.onResume();activityResumed=true;mapView?.let { it.onResume();mapResumed=true } }
    override fun onPause() { if(mapResumed)mapView?.onPause();mapResumed=false;activityResumed=false;super.onPause() }
    override fun onStop() {
        projectionWatch?.cancel();projectionWatch=null
        activityStarted=false;generation++;cancelLocation();pauseDownload("Paused when you left this screen.",false)
        if(mapStarted)mapView?.onStop();mapStarted=false
        if(offlinePreview)MapLibre.setConnected(null)
        super.onStop()
    }
    override fun onDestroy() { watchScope.cancel();cancelLocation();pauseDownload("",false);closeMap();main.removeCallbacksAndMessages(null);super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory();mapView?.onLowMemory() }
    @Deprecated("Keeps map setup and explicit download pause in this Activity")
    override fun onBackPressed() { if(deleting)return;if(page==Page.EDIT)finish() else { pauseDownload("",false);showEditor() } }

    private fun reconcileProjection() {
        if(!activityStarted||isFinishing||isDestroyed)return
        if(page==Page.EDIT)refreshViewport()
        if(!ServiceState.projectionActive)return
        if(activeArea!=null||offlinePreview) {
            pauseDownload("Offline setup paused because a car connection started.",false)
            closeMap();showList(lastNotice)
        }
    }

    private fun screen(title:String,next:Page) {
        generation++;cancelLocation();closeMap();page=next;coordinates=null;progress=null
        column=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(28));setBackgroundColor(CarUi.BACKGROUND)
        }
        setContentView(ScrollView(this).apply { addView(column) })
        text(title,26f);status=text("",15f,true)
    }
    private fun showList(notice:String=lastNotice) {
        screen("Downloaded areas",Page.LIST);lastNotice=notice;status?.text=notice
        button(column,"Back to map",true) { showEditor() }
        text("Green areas include maps, offline directions and address search. Incomplete areas can resume here.",16f,true)
        text("Downloads pause when you leave this screen. Resume them here. Ready areas are kept after the app or phone restarts.",14f,true)
        val host=column;val ticket=generation
        val loading=text("Checking saved areas…",15f,true)
        manager.listOfflineRegions(object:OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions:Array<OfflineRegion>?) {
                if(!current(ticket)||page!=Page.LIST)return
                areas=groupAreas(regions)
                loadNavigationReadiness(ticket) {
                    loading.visibility=View.GONE
                    if(areas.isEmpty())text("No offline areas yet. Return to the map, pan and zoom, then download the visible view.",17f,true,host)
                    for(area in areas)regionCard(area,ticket,host)
                    button(host,"Done") { showEditor() }
                }
            }
            override fun onError(error:String) { if(current(ticket))status?.text="Saved maps could not be read. Try opening this screen again." }
        })
    }
    private fun regionCard(area:Area,ticket:Long,host:LinearLayout) {
        val card=card(host);text(area.name,21f,parent=card)
        text(dimensions(area.bounds)+" · street detail · © OpenMapTiles.org · © OpenStreetMap",14f,true,card)
        val state=text("Checking download…",16f,true,card)
        val resume=button(card,"Resume download",true) { beginDownload(area) }.apply { isEnabled=false }
        val preview=button(card,"Preview without internet") { preview(area) }.apply { isEnabled=false }
        val verify=button(card,"Verify downloaded area",description="openauto.offline.verify") {
            AlertDialog.Builder(this).setTitle("Verify this downloaded area?")
                .setMessage("Remove temporary map cache, then open this area with map networking disabled. Downloaded areas and Saved addresses stay. This checks that ordinary browsing cache is not filling gaps.")
                .setNegativeButton("Cancel",null).setPositiveButton("Verify") { _,_->verify(area) }.show()
        }.apply { isEnabled=false }
        button(card,"Delete area") {
            AlertDialog.Builder(this).setTitle("Delete this offline area?").setMessage(area.name+"\nSaved addresses will stay. Shared or ordinary cached resources may remain until cache eviction.")
                .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_->delete(area) }.show()
        }
        fun refresh() {
            if(!current(ticket))return
            val complete=area.complete()
            val visual=area.visualComplete()
            val checked=area.regions.all { area.statuses.containsKey(it.key) }
            state.text=when { complete->"Ready for offline navigation";!checked->"Checking download…";visual->"Map saved · finish directions and addresses";else->"Paused or incomplete · resume to finish" }
            val exhausted=OfflineMapHttp.requestsUsed(this,area.id)>=OfflineRequestBudget.MAX_REQUESTS
            if(exhausted&&!visual)state.text="Download allowance reached. Delete this incomplete area and choose a smaller area."
            resume.visibility=if(complete)View.GONE else View.VISIBLE
            resume.isEnabled=checked&&!complete&&(!exhausted||visual)
            preview.isEnabled=visual
            verify.isEnabled=visual
        }
        if(area.regions.isEmpty())refresh()
        for((density,region) in area.regions)region.getStatus(object:OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(value:OfflineRegionStatus?) { if(!current(ticket))return;if(value!=null)area.statuses[density]=value;refresh() }
            override fun onError(error:String?) { if(current(ticket))state.text="Download status is unavailable. Reopen this screen before resuming." }
        })
    }
    private fun showEditor() {
        // A paused download retains the global bounded HTTP client until native inactive
        // barriers return. Wait for that ownership release before opening an online map.
        if(pendingClosures>0) { returnToPickerAfterCleanup=true;progress?.text="Finishing the pause…";return }
        returnToPickerAfterCleanup=false
        generation++;cancelLocation();closeMap();page=Page.EDIT;coordinates=null;progress=null
        pickerLoaded=false;pickerMoving=false
        column=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(10));setBackgroundColor(CarUi.BACKGROUND)
        }
        setContentView(column)
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        column.addView(header)
        val back=CarUi.button(this,"‹",false).apply { contentDescription="Back to Maps & Saved";setOnClickListener { finish() } }
        header.addView(back,LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(CarUi.text(this,"Offline maps",23f,CarUi.TEXT),LinearLayout.LayoutParams(0,dp(48),1f))
        val menu=CarUi.button(this,"⋮",false).apply { contentDescription="Offline map options" }
        header.addView(menu,LinearLayout.LayoutParams(dp(48),dp(48)))
        menu.setOnClickListener { PopupMenu(this,menu).apply {
            this.menu.add("Downloaded areas").setOnMenuItemClickListener { showList();true }
            this.menu.add("Go to coordinates").setOnMenuItemClickListener { coordinateDialog();true }
            this.menu.add("About offline maps").setOnMenuItemClickListener {
                AlertDialog.Builder(this@OfflineMapsActivity).setTitle("Offline maps")
                    .setMessage("Pan and zoom to select the exact visible rectangle. Green outlines are ready; amber areas are incomplete. Tap an outline to manage it.\n\nMaps, road data and indexed addresses stay after restart. Green means all three are ready. Downloads pause when you leave this screen or connect to a car.\n\nUp to 64 small areas. Each area is limited to 21 km per side and a conservative tile allowance. Map visuals use at most 4 parallel requests and pause around 45 MB; in-flight data can exceed that soft threshold. Shared regional road files download one at a time and may be up to 250 MB each."+decodeDiagnostic())
                    .setPositiveButton("Done",null).show();true
            }
            show()
        } }
        val centers=LinearLayout(this)
        column.addView(centers)
        for((label,action) in listOf<Pair<String,()->Unit>>("Saved" to { chooseSaved() },"My location" to { useLocation() })) {
            val control=CarUi.button(this,label,false).apply { setOnClickListener { action() } }
            centers.addView(control,LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd=dp(4) })
        }
        text("Pan and zoom · the visible rectangle is saved",14f,true)
        val view=createMap(false,-1)
        status=text(lastNotice.ifBlank { "Green = ready · Amber = incomplete" },13f,true)
        pickerSummary=text("Loading map and downloaded areas…",14f,true)
        pickerDownload=button(column,"Download this view",true,"openauto.offline.download") { requestViewportDownload() }.apply { isEnabled=false }
        val ticket=generation
        manager.listOfflineRegions(object:OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions:Array<OfflineRegion>?) {
                if(!current(ticket)||page!=Page.EDIT)return
                areas=groupAreas(regions);drawOutlines();refreshViewport()
                loadNavigationReadiness(ticket) { pickerLoaded=true;drawOutlines();refreshViewport() }
                for(area in areas)for((density,region) in area.regions)region.getStatus(object:OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(value:OfflineRegionStatus?) {
                        if(!current(ticket)||page!=Page.EDIT)return
                        if(value!=null)area.statuses[density]=value
                        main.removeCallbacks(redrawOutlines);main.postDelayed(redrawOutlines,80)
                    }
                    override fun onError(error:String?) { /* Incomplete stays amber; never infer readiness. */ }
                })
            }
            override fun onError(error:String) {
                if(current(ticket)) { status?.visibility=View.VISIBLE;status?.text="Saved areas could not be read. Reopen Offline maps before downloading." }
            }
        })
        view.getMapAsync { map ->
            if(mapView!==view||page!=Page.EDIT)return@getMapAsync
            pickerMap=map
            map.cameraPosition=CameraPosition.Builder().target(LatLng(latitude,longitude)).zoom(pickerZoom).bearing(0.0).tilt(0.0).build()
            map.setStyle(NavigationConfig.DEFAULT_STYLE) { if(mapView===view) { drawOutlines();view.post { refreshViewport() } } }
            map.addOnCameraMoveStartedListener { if(mapView===view) { pickerMoving=true;refreshViewport() } }
            map.addOnCameraIdleListener {
                if(mapView===view) {
                    pickerMoving=false
                    map.cameraPosition.target?.let { latitude=it.latitude;longitude=it.longitude }
                    pickerZoom=map.cameraPosition.zoom;refreshViewport()
                }
            }
            map.addOnMapClickListener { point ->
                if(mapView!==view||!pickerLoaded)false else {
                    val hits=OfflineAreaSelection.containing(areas.map { it.bounds },point.latitude,point.longitude)
                    when(hits.size) {
                        0 -> false
                        1 -> { areaActions(areas[hits[0]]);true }
                        else -> {
                            AlertDialog.Builder(this).setTitle("Choose an overlapping area")
                                .setItems(hits.map { areas[it].name+" · "+dimensions(areas[it].bounds) }.toTypedArray()) { _,index -> areaActions(areas[hits[index]]) }
                                .setNegativeButton("Cancel",null).show();true
                        }
                    }
                }
            }
        }
    }
    private fun viewportBounds():OfflineAreaPolicy.Bounds {
        val map=pickerMap?:throw IllegalArgumentException("Loading map…")
        val view=mapView?:throw IllegalArgumentException("Loading map…")
        if(view.width<=0||view.height<=0||pickerMoving)throw IllegalArgumentException("Finish moving the map to select an area")
        // No camera padding, bearing or tilt: this is the actual visible map rectangle,
        // not a center/radius approximation. The footer sits outside the MapView.
        val bounds=map.projection.visibleRegion.latLngBounds
        return OfflineAreaPolicy.validate(bounds.latitudeNorth,bounds.longitudeEast,bounds.latitudeSouth,bounds.longitudeWest)
            .also(OfflineAreaPolicy::requireDownloadable)
    }
    private fun refreshViewport() {
        if(page!=Page.EDIT)return
        val unavailable=when {
            !pickerLoaded -> "Checking downloaded areas…"
            pickerMap==null -> "Loading map…"
            ServiceState.projectionActive -> "Disconnect the car or desktop before downloading"
            NavigationConfig.load(this).styleUrl!=NavigationConfig.DEFAULT_STYLE -> "Downloads use the default OpenFreeMap style; change your custom style in Maps setup first"
            areas.size>=OfflineAreaPolicy.MAX_AREAS -> "64 areas saved. Delete an area before downloading another"
            pickerMoving -> "Finish moving the map to select an area"
            else -> null
        }
        val bounds=if(unavailable==null)try { viewportBounds() } catch(_:IllegalArgumentException) { null } else null
        pickerSummary?.text=unavailable?:if(bounds==null)"Zoom in to a smaller area · up to 21 km per side" else
            dimensions(bounds)+" · "+areas.size+" saved\nIncludes maps, directions and address search"
        pickerDownload?.isEnabled=bounds!=null&&unavailable==null
    }
    private fun requestViewportDownload() {
        if(!pickerLoaded||pickerMoving||areas.size>=OfflineAreaPolicy.MAX_AREAS||!canDownload())return
        val bounds=try { viewportBounds() } catch(_:IllegalArgumentException) { refreshViewport();return }
        val input=EditText(this).apply {
            setSingleLine(true);hint="Area name";filters=arrayOf(InputFilter.LengthFilter(60))
            setText(areaName.ifBlank { "Map area ${areas.size+1}" });selectAll()
        }
        val dialog=AlertDialog.Builder(this).setTitle("Download this view?")
            .setMessage(dimensions(bounds)+"\nIncludes maps, offline directions and addresses. Nearby areas share road data; the first regional download can be tens or hundreds of MB.")
            .setView(input).setNegativeButton("Cancel",null).setPositiveButton("Download",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name=input.text.toString().trim().take(60)
            if(name.isBlank()) { input.error="Give this area a name";return@setOnClickListener }
            // Bounds are deliberately captured before the prompt; this is the view the user selected.
            dialog.dismiss();areaName="";beginDownload(Area(UUID.randomUUID().toString(),name,bounds,0))
        } }
        dialog.show()
    }
    @Suppress("DEPRECATION")
    private fun drawOutlines() {
        val map=pickerMap?:return
        if(page!=Page.EDIT||map.style?.isFullyLoaded!=true)return
        for(polygon in outlines)map.removePolygon(polygon)
        for(line in outlineEdges)map.removePolyline(line)
        outlines.clear();outlineEdges.clear()
        for(area in areas) {
            val b=area.bounds;val complete=area.complete()
            val points=listOf(LatLng(b.north,b.west),LatLng(b.north,b.east),LatLng(b.south,b.east),LatLng(b.south,b.west),LatLng(b.north,b.west))
            val color=if(complete)0xff53ca85.toInt() else 0xffe7ae50.toInt()
            // The legacy annotation renderer needs explicit opacity; ARGB alpha alone
            // whitewashes its premultiplied fill. Keep the boundary separately opaque.
            outlines+=map.addPolygon(PolygonOptions().addAll(points).fillColor(color).alpha(0.06f).strokeColor(color))
            outlineEdges+=map.addPolyline(PolylineOptions().addAll(points).color(color).alpha(1f).width(2f))
        }
    }

    private fun areaActions(area:Area) {
        val actions=mutableListOf<Pair<String,()->Unit>>()
        if(area.visualComplete()) {
            actions+="Preview without internet" to { preview(area) }
            actions+="Verify downloaded area" to { confirmVerify(area) }
        }
        if(!area.complete()&&(area.visualComplete()||OfflineMapHttp.requestsUsed(this,area.id)<OfflineRequestBudget.MAX_REQUESTS)) {
            actions+="Resume download" to { beginDownload(area) }
        }
        actions+="Delete area" to { confirmDelete(area) }
        AlertDialog.Builder(this).setTitle(area.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _,index -> actions[index].second() }
            .setNegativeButton("Cancel",null).show()
    }
    private fun confirmDelete(area:Area) {
        AlertDialog.Builder(this).setTitle("Delete this offline area?")
            .setMessage(area.name+"\nSaved addresses will stay. Shared or ordinary cached resources may remain until cache eviction.")
            .setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_->delete(area) }.show()
    }
    private fun confirmVerify(area:Area) {
        AlertDialog.Builder(this).setTitle("Verify this downloaded area?")
            .setMessage("Remove temporary map cache, then open this area with map networking disabled. Downloaded areas and Saved addresses stay. This checks that ordinary browsing cache is not filling gaps.")
            .setNegativeButton("Cancel",null).setPositiveButton("Verify") { _,_->verify(area) }.show()
    }
    private fun coordinateDialog() {
        val input=EditText(this).apply { setSingleLine(true);hint="latitude, longitude";setText(coordinates(latitude,longitude));filters=arrayOf(InputFilter.LengthFilter(80)) }
        val dialog=AlertDialog.Builder(this).setTitle("Go to coordinates").setView(input)
            .setNegativeButton("Cancel",null).setPositiveButton("Go",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val point=try {
                val parts=input.text.toString().split(',');require(parts.size==2)
                val lat=parts[0].trim().toDouble();val lon=parts[1].trim().toDouble()
                require(lat.isFinite()&&lon.isFinite()&&lat in -80.0..80.0&&lon in -180.0..180.0);Pair(lat,lon)
            } catch(_:IllegalArgumentException) { input.error="Enter latitude, longitude away from the poles";null }
            if(point!=null) { dialog.dismiss();latitude=point.first;longitude=point.second;pickerZoom=14.0;showEditor() }
        } };dialog.show()
    }
    private fun chooseSaved() {
        val saved=DestinationPreferences.open(this).snapshot().saved
        if(saved.isEmpty()) { status?.text="Add a Saved place from Maps & Saved first, or enter coordinates.";return }
        AlertDialog.Builder(this).setTitle("Center on a Saved place").setItems(saved.map { it.name }.toTypedArray()) { _,index ->
            val place=saved[index];latitude=place.latitude;longitude=place.longitude;coordinateText=coordinates(latitude,longitude)
            areaName=place.name.take(60);pickerZoom=14.0
            showEditor()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun useLocation() {
        if (!org.openandroidauto.integration.ProjectionAvailability.isSupported()) {
            status?.text="Choose a Saved place or enter coordinates on this Android version.";return
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            status?.text="Precise location is not allowed. Use a Saved place or type coordinates; app permissions are in phone setup.";return
        }
        val locations=getSystemService(LocationManager::class.java)
        val provider=try { listOf(LocationManager.NETWORK_PROVIDER,LocationManager.GPS_PROVIDER).firstOrNull { locations?.isProviderEnabled(it)==true } }
            catch(_:RuntimeException) { null }
        if(locations==null||provider==null) { status?.text="Turn on phone Location, or choose a Saved place.";return }
        cancelLocation();val cancellation=CancellationSignal();locationRequest=cancellation;val ticket=generation
        status?.text="Getting the phone's location…"
        main.postDelayed({ if(locationRequest===cancellation) { cancellation.cancel();locationRequest=null;if(current(ticket))status?.text="Location did not arrive. Choose a Saved place or enter coordinates." } },20_000)
        try { locations.getCurrentLocation(provider,cancellation,mainExecutor) { location ->
            if(locationRequest!==cancellation||!current(ticket)||page!=Page.EDIT)return@getCurrentLocation
            cancelLocation()
            if(location==null) { status?.text="Location is unavailable. Choose a Saved place or enter coordinates.";return@getCurrentLocation }
            latitude=location.latitude;longitude=location.longitude;coordinateText=coordinates(latitude,longitude);pickerZoom=14.0;showEditor()
        } } catch(_:RuntimeException) { cancelLocation();status?.text="Location could not be read. Choose a Saved place or enter coordinates." }
    }
    private fun parseCoordinates():Pair<Double,Double>? {
        val parts=coordinateText.split(',')
        val point=try { if(parts.size==2)Pair(parts[0].trim().toDouble(),parts[1].trim().toDouble()) else null } catch(_:NumberFormatException) { null }
        try { if(point==null)throw IllegalArgumentException();OfflineAreaPolicy.square(point.first,point.second,areaWidth) }
        catch(_:IllegalArgumentException) { status?.text="Enter latitude, longitude for a small area away from the poles and date line.";coordinates?.error="Check these coordinates";return null }
        return point
    }
    private fun canDownload():Boolean {
        if(deleting) { status?.text="Wait for the current deletion to finish.";return false }
        if(ServiceState.projectionActive) { status?.text="Stop the car or desktop connection before downloading or testing offline maps.";return false }
        if(NavigationConfig.load(this).styleUrl!=NavigationConfig.DEFAULT_STYLE) {
            status?.text="Offline downloads currently support the default OpenFreeMap style. Your custom style is left unchanged.";return false
        }
        return true
    }
    private fun beginDownload(area:Area) {
        if(!activityStarted||!canDownload()||activeArea!=null)return
        try { OfflineAreaPolicy.requireDownloadable(area.bounds) } catch(error:IllegalArgumentException) { status?.text=error.message;return }
        if(!area.visualComplete()&&OfflineMapHttp.requestsUsed(this,area.id)>=OfflineRequestBudget.MAX_REQUESTS) { status?.text="This area's download allowance is exhausted. Choose a smaller area.";return }
        screen("Downloading offline area",Page.DOWNLOAD)
        text(area.name,22f);text("Keep this screen open. Leaving pauses the download. Maps, road data and addresses are saved in turn. Shared road data is downloaded once for nearby areas.",16f,true)
        progress=text("Preparing the saved map…",18f)
        button(column,"Pause download") { pauseDownload("Download paused.",true) }
        activeArea=area;val ticket=++operation
        if(area.visualComplete()) { beginNavigationData(area,ticket);return }
        try { transport=OfflineMapHttp.start(this,area.id,
            { if(activeArea===area)pauseDownload("Download allowance reached. Choose a smaller area.",true) },
            { if(activeArea===area)pauseDownload("Download paused because a car connection started.",true) }) }
        catch(_:RuntimeException) { activeArea=null;showList("The downloader could not start. Try again.");return }
        ensureRegions(area,ticket,0)
    }
    private fun ensureRegions(area:Area,ticket:Long,index:Int) {
        if(!downloading(area,ticket))return
        if(index>=DENSITIES.size) { downloadNext(area,ticket,0);return }
        val density=DENSITIES[index]
        if(area.regions.containsKey(density)) { ensureRegions(area,ticket,index+1);return }
        val bounds=LatLngBounds.from(area.bounds.north,area.bounds.east,area.bounds.south,area.bounds.west)
        val definition=OfflineTilePyramidRegionDefinition(NavigationConfig.DEFAULT_STYLE,bounds,0.0,OfflineAreaPolicy.MAX_ZOOM.toDouble(),density.toFloat(),false)
        manager.createOfflineRegion(definition,encode(area,density),object:OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region:OfflineRegion) {
                if(!downloading(area,ticket))return // Newly created regions are inactive until explicitly resumed.
                area.regions[density]=region;ensureRegions(area,ticket,index+1)
            }
            override fun onError(error:String) { if(downloading(area,ticket))pauseDownload("Could not prepare the package. Resume it here later.",true) }
        })
    }
    private fun downloadNext(area:Area,ticket:Long,index:Int) {
        if(!downloading(area,ticket))return
        if(index>=DENSITIES.size) { beginNavigationData(area,ticket);return }
        val density=DENSITIES[index];val region=area.regions[density]?:return
        region.getStatus(object:OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(value:OfflineRegionStatus?) {
                if(!downloading(area,ticket))return
                if(ready(value)) { area.statuses[density]=value!!;downloadNext(area,ticket,index+1);return }
                activeRegion=region
                region.setObserver(object:OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status:OfflineRegionStatus) {
                        if(!downloading(area,ticket)||activeRegion!==region)return
                        area.statuses[density]=status
                        val packageBytes=area.statuses.values.sumOf { it.completedResourceSize }
                        val count=if(status.isRequiredResourceCountPrecise&&status.requiredResourceCount>0)
                            "${(100*status.completedResourceCount/status.requiredResourceCount).coerceIn(0,100)}%" else "${status.completedResourceCount} resources"
                        progress?.text="Part ${index+1} of 2 · $count\n${String.format(Locale.US,"%.1f",packageBytes/1_000_000.0)} MB of package resources"
                        if(packageBytes>=OfflineAreaPolicy.SOFT_PAUSE_BYTES&&!area.visualComplete()) {
                            pauseDownload("Paused at the soft package-size limit. Choose a smaller area.",true);return
                        }
                        if(ready(status)) {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE);region.setObserver(null);activeRegion=null
                            downloadNext(area,ticket,index+1)
                        }
                    }
                    override fun onError(error:OfflineRegionError) { if(downloading(area,ticket)&&activeRegion===region)pauseDownload("Download interrupted. Check the connection, then resume here.",true) }
                    override fun mapboxTileCountLimitExceeded(limit:Long) { if(downloading(area,ticket))pauseDownload("The map library's storage limit was reached. Delete an area before continuing.",true) }
                })
                region.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }
            override fun onError(error:String?) { if(downloading(area,ticket))pauseDownload("Download status is unavailable. Try again later.",true) }
        })
    }
    private fun beginNavigationData(area:Area,ticket:Long) {
        if(!downloading(area,ticket))return
        // Drain native map requests before replacing the downloader owner. This next
        // stage uses its own bounded HTTP requests and never creates a MapView.
        pauseDownload("",false)
        activeArea=area;val navigationTicket=++operation
        val cancellation=CancellationSignal();navigationCancellation=cancellation
        progress?.text="Preparing offline directions…"
        navigationDownload=watchScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    OfflineNavigationDownloader.download(this@OfflineMapsActivity,area.id,area.bounds,cancellation) { stage,done,total ->
                        main.post {
                            if(downloading(area,navigationTicket))progress?.text=stage+"\n"+
                                if(total>0)String.format(Locale.US,"%.1f / %.1f MB",done/1_000_000.0,total/1_000_000.0)
                                else String.format(Locale.US,"%.1f MB",done/1_000_000.0)
                        }
                    }
                }
                if(downloading(area,navigationTicket)) {
                    area.navigationReady=true;navigationCancellation=null;navigationDownload=null
                    pauseDownload("${area.name} is ready for offline navigation.",true)
                }
            } catch(_:CancellationException) { cancellation.cancel() }
            catch(_:android.os.OperationCanceledException) { /* Explicit pause preserves resumable road data. */ }
            catch(error:Exception) {
                if(downloading(area,navigationTicket)) {
                    navigationCancellation=null;navigationDownload=null
                    pauseDownload(error.message?.take(180)?:"Navigation download interrupted. Resume to finish.",true)
                }
            }
        }
    }
    private fun downloading(area:Area,ticket:Long)=activityStarted&&!isFinishing&&!isDestroyed&&activeArea===area&&operation==ticket
    private fun pauseDownload(message:String,show:Boolean) {
        val stopping=navigationDownload
        if(stopping!=null) {
            pendingClosures++
            stopping.invokeOnCompletion {
                main.post {
                    pendingClosures--
                    if(pendingClosures==0&&returnToPickerAfterCleanup&&activityStarted&&!isFinishing&&!isDestroyed)showEditor()
                }
            }
        }
        navigationCancellation?.cancel();navigationCancellation=null
        navigationDownload?.cancel();navigationDownload=null
        val area=activeArea
        operation++;activeArea=null;activeRegion=null
        val closing=transport;transport=null
        closing?.pauseRequests()
        if(closing!=null)pendingClosures++
        if(show)returnToPickerAfterCleanup=true
        var finished=false
        fun finishClosing() {
            if(finished)return
            finished=true;closing?.close()
            if(closing!=null)pendingClosures--
            if(pendingClosures==0&&returnToPickerAfterCleanup&&activityStarted&&!isFinishing&&!isDestroyed)showEditor()
        }
        if(message.isNotBlank())lastNotice=message
        if(area!=null&&area.regions.isNotEmpty()) {
            var remaining=area.regions.size
            for(region in area.regions.values) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE);region.setObserver(null)
                // getStatus is queued after the inactive setter in the native file-source
                // worker. Keep the paused, budgeted HTTP factory until that queue barrier
                // returns, so a late native request cannot fall onto the unbounded default.
                region.getStatus(object:OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status:OfflineRegionStatus?) { finishOne() }
                    override fun onError(error:String?) { finishOne() }
                    private fun finishOne() { remaining--;if(remaining==0)finishClosing() }
                })
            }
        } else finishClosing()
    }
    private fun delete(area:Area) {
        if(deleting)return
        if(ServiceState.projectionActive) { status?.text="Stop the car connection before deleting a map area.";return }
        deleting=true;screen("Deleting offline area",Page.DOWNLOAD)
        // The readiness record is removed before any map resources, so a failed
        // partial deletion cannot remain eligible for offline navigation.
        watchScope.launch {
            try { withContext(Dispatchers.IO) { OfflineNavigationDownloader.removeArea(this@OfflineMapsActivity,area.id) } }
            catch(_:Exception) { deleting=false;showList("Navigation data could not be removed. Try again.");return@launch }
            deleteVisualArea(area)
        }
    }
    private fun deleteVisualArea(area:Area) {
        var remaining=area.regions.size;var failed=false
        text("Deleting ${area.name}…",18f)
        for(region in area.regions.values) {
            region.setDownloadState(OfflineRegion.STATE_INACTIVE);region.setObserver(null)
            region.delete(object:OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() { finishOne(false) }
                override fun onError(error:String) { finishOne(true) }
                private fun finishOne(error:Boolean) {
                    failed=failed||error;remaining--
                    if(remaining==0) {
                        deleting=false
                        if(!failed)OfflineMapHttp.forget(this@OfflineMapsActivity,area.id)
                        lastNotice=if(failed)"Some map data could not be deleted. Reopen the list and try again." else "Offline area deleted. Saved addresses are unchanged."
                        if(activityStarted&&!isFinishing&&!isDestroyed)showEditor()
                    }
                }
            })
        }
    }
    private fun preview(area:Area) {
        if(ServiceState.projectionActive) { status?.text="Stop the car connection before testing the offline map.";return }
        screen("Offline map preview",Page.PREVIEW);offlinePreview=true;MapLibre.setConnected(false)
        text(area.name,22f);text("Map networking is disabled for this preview. This preview checks the saved map. Use address search and navigation with Wi-Fi and mobile data off to test the whole area.",16f,true)
        val view=createMap(true,440)
        view.getMapAsync { map ->
            if(mapView!==view||page!=Page.PREVIEW)return@getMapAsync
            map.setMaxZoomPreference(16.0)
            map.setLatLngBoundsForCameraTarget(LatLngBounds.from(area.bounds.north,area.bounds.east,area.bounds.south,area.bounds.west))
            map.cameraPosition=CameraPosition.Builder().target(LatLng(area.bounds.latitude(),area.bounds.longitude())).zoom(14.0).build()
            map.setStyle(NavigationConfig.DEFAULT_STYLE)
        }
        button(column,"Back to map") { showEditor() }
    }
    private fun verify(area:Area) {
        if(ServiceState.projectionActive) { status?.text="Stop the car connection before verifying an offline area.";return }
        screen("Verifying saved map",Page.DOWNLOAD)
        text("Clearing temporary map cache. Downloaded areas and Saved addresses are kept.",16f,true)
        val ticket=generation
        manager.clearAmbientCache(object:OfflineManager.FileSourceCallback {
            override fun onSuccess() {
                if(!current(ticket)||!activityStarted)return
                if(ServiceState.projectionActive) { showList("Verification stopped because a car connection started.");return }
                preview(area)
            }
            override fun onError(message:String) { if(current(ticket))showList("Temporary cache could not be cleared. Verification has not run.") }
        })
    }
    private fun createMap(preview:Boolean,height:Int):MapView {
        text("© OpenMapTiles.org · © OpenStreetMap",12f,true)
        val options=MapLibreMapOptions().compassEnabled(false).rotateGesturesEnabled(false).tiltGesturesEnabled(false).attributionEnabled(true)
        if(preview)options.pixelRatio(1f)
        val view=MapView(this,options)
        mapView=view;view.onCreate(null);view.setMaximumFps(15)
        view.addOnDidFailLoadingMapListener { if(mapView===view)main.post { if(mapView===view)status?.text=if(preview)"Some saved map resources are unavailable. Reconnect and finish or download this area again." else "Map preview is unavailable. Check the connection and coordinates." } }
        column.addView(view,if(height<0)LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(height)))
        if(activityStarted) { view.onStart();mapStarted=true };if(activityResumed) { view.onResume();mapResumed=true }
        return view
    }
    private fun closeMap() {
        main.removeCallbacks(redrawOutlines);pickerMap=null;outlines.clear();outlineEdges.clear();pickerDownload=null;pickerSummary=null
        val view=mapView;mapView=null
        if(view!=null) { if(mapResumed)view.onPause();if(mapStarted)view.onStop();view.onDestroy() }
        mapResumed=false;mapStarted=false
        if(offlinePreview) { MapLibre.setConnected(null);offlinePreview=false }
    }
    private fun cancelLocation() { locationRequest?.cancel();locationRequest=null }
    private fun encode(area:Area,density:Int)=JSONObject().put("owner",if(area.width==0)VIEWPORT_OWNER else OWNER).put("id",area.id).put("name",area.name).put("density",density)
        .put("north",area.bounds.north).put("east",area.bounds.east).put("south",area.bounds.south).put("west",area.bounds.west).put("width",area.width)
        .toString().toByteArray(Charsets.UTF_8)
    private fun decode(region:OfflineRegion):Pair<Area,Int>? {
        var owned=false
        fun reject(reason:String):Pair<Area,Int>? { decodeFailures[reason]=(decodeFailures[reason]?:0)+1;return null }
        try {
            val bytes=region.metadata
            if(bytes.size>8192)return null
            val data=JSONObject(bytes.toString(Charsets.UTF_8))
            val owner=data.optString("owner")
            if(owner!=OWNER&&owner!=VIEWPORT_OWNER)return null
            owned=true
            val id=data.getString("id");UUID.fromString(id)
            val density=data.getInt("density");val name=data.getString("name");val width=data.getInt("width")
            val validOwner=(owner==OWNER&&width in OfflineAreaPolicy.WIDTH_KM)||(owner==VIEWPORT_OWNER&&width==0)
            if(!validOwner||name.isBlank()||name.length>60||density !in DENSITIES)return reject("metadata")
            val bounds=try {
                OfflineAreaPolicy.validate(data.getDouble("north"),data.getDouble("east"),data.getDouble("south"),data.getDouble("west"))
                    .also(OfflineAreaPolicy::requireDownloadable)
            } catch(_:IllegalArgumentException) { return reject("geometry or tile allowance") }
            val definition=region.definition as? OfflineTilePyramidRegionDefinition?:return reject("definition type")
            if(definition.styleURL!=NavigationConfig.DEFAULT_STYLE)return reject("map style")
            if(definition.minZoom!=0.0||definition.maxZoom!=OfflineAreaPolicy.MAX_ZOOM.toDouble())return reject("zoom range")
            if(definition.pixelRatio!=density.toFloat())return reject("screen density")
            val native=definition.bounds?:return reject("missing native bounds")
            if(!OfflineAreaPolicy.matchesNativeBounds(bounds,native.latitudeNorth,native.longitudeEast,native.latitudeSouth,native.longitudeWest))
                return reject("native bounds")
            if(native.latitudeNorth!=bounds.north||native.latitudeSouth!=bounds.south||native.longitudeEast!=bounds.east||native.longitudeWest!=bounds.west)
                normalizedNativeBounds++
            return Pair(Area(id,name,bounds,width),density)
        } catch(_:Exception) { return if(owned)reject("metadata") else null }
    }
    private fun decodeDiagnostic():String {
        val ignored=decodeFailures.entries.joinToString { it.key+": "+it.value }
        return "\n\nSaved-region check: ${areas.size} areas; $normalizedNativeBounds native round-off matches."+
            if(ignored.isBlank())" No owned regions ignored." else " Ignored: $ignored."
    }
    private fun groupAreas(regions:Array<OfflineRegion>?):List<Area> {
        decodeFailures.clear();normalizedNativeBounds=0
        val grouped=linkedMapOf<String,Area>()
        for(region in regions.orEmpty()) {
            val decoded=decode(region)?:continue
            val (candidate,density)=decoded
            val existing=grouped[candidate.id]
            if(existing!=null) {
                val a=existing.bounds;val b=candidate.bounds
                if(existing.name!=candidate.name||existing.width!=candidate.width||a.north!=b.north||a.east!=b.east||a.south!=b.south||a.west!=b.west)continue
                if(existing.regions.containsKey(density))continue
                existing.regions[density]=region
            } else { candidate.regions[density]=region;grouped[candidate.id]=candidate }
        }
        return grouped.values.toList()
    }
    private fun loadNavigationReadiness(ticket:Long,finished:()->Unit) {
        watchScope.launch {
            val ready=withContext(Dispatchers.IO) { OfflineNavigationStore.readyIds(this@OfflineMapsActivity) }
            if(current(ticket)) {
                for(area in areas)area.navigationReady=area.id in ready
                finished()
            }
        }
    }
    private fun dimensions(bounds:OfflineAreaPolicy.Bounds)=String.format(Locale.getDefault(),"%.1f × %.1f km",OfflineAreaPolicy.widthKilometers(bounds),OfflineAreaPolicy.heightKilometers(bounds))
    private fun current(ticket:Long)=generation==ticket&&!isFinishing&&!isDestroyed
    private fun card(parent:LinearLayout)=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;background=CarUi.rounded(this@OfflineMapsActivity,CarUi.SURFACE,16);setPadding(dp(14),dp(12),dp(14),dp(12))
        parent.addView(this,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(12) })
    }
    private fun text(value:String,size:Float=16f,muted:Boolean=false,parent:LinearLayout=column)=CarUi.text(this,value,size,if(muted)CarUi.TEXT_MUTED else CarUi.TEXT).also {
        it.setPadding(0,dp(6),0,dp(6));parent.addView(it)
    }
    private fun button(parent:LinearLayout,label:String,primary:Boolean=false,description:String?=null,action:()->Unit)=CarUi.button(this,label,primary).also {
        it.setOnClickListener { action() };if(description!=null)it.contentDescription=description
        parent.addView(it,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(8) })
    }
    private fun field(hint:String,value:String,limit:Int,changed:(String)->Unit)=EditText(this).apply {
        setSingleLine(true);this.hint=hint;setTextColor(CarUi.TEXT);setHintTextColor(CarUi.TEXT_MUTED);filters=arrayOf(InputFilter.LengthFilter(limit));setText(value)
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s:Editable?) {}
        });column.addView(this)
    }
    private fun dp(value:Int)=CarUi.dp(this,value.toFloat())
    private fun coordinates(latitude:Double,longitude:Double)=String.format(Locale.US,"%.6f, %.6f",latitude,longitude)
}
