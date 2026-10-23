// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidPlaybackState
import android.net.Uri
import android.os.*
import org.bluetoothauto.artwork.MapArtworkRenderer
import org.bluetoothauto.artwork.DistanceRefreshPolicy
import org.bluetoothauto.model.*
import org.bluetoothauto.navigation.GuidanceFormatter
import org.bluetoothauto.navigation.GuidanceChangePolicy
import org.bluetoothauto.playback.VlcPlayer
import org.bluetoothauto.playback.MediaArtworkLoader
import org.bluetoothauto.navigation.OwnNavigation
import org.bluetoothauto.navigation.CueSpeaker
import org.bluetoothauto.navigation.CuePolicy
import org.bluetoothauto.navigation.TurnGeometryResolver
import org.bluetoothauto.navigation.OwnTurnGeometry
import org.bluetoothauto.navigation.TravelCourseMemory
import org.bluetoothauto.navigation.UpcomingTurnPlanner
import org.bluetoothauto.navigation.NotificationRouteTracker
import org.bluetoothauto.navigation.NotificationRouteResolver
import org.bluetoothauto.integration.OfficialProjectionMonitor
import org.bluetoothauto.integration.OfficialProjectionWorkPolicy
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.PrintWriter

class BluetoothAutoService : Service(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: VlcPlayer
    private lateinit var session: MediaSession
    private lateinit var renderer: MapArtworkRenderer
    private lateinit var audio: AudioManager
    private lateinit var locations: LocationManager
    private lateinit var turnResolver:TurnGeometryResolver
    private val officialProjectionWork = OfficialProjectionWorkPolicy()
    private var officialProjectionMonitor: Closeable? = null
    private fun navigationWorkAllowed() = !destroyed && !stopping && officialProjectionWork.navigationAllowed
    private var lastObservedGuidance:Guidance?=null
    private var turnRequest:Closeable?=null
    private var turnRequestKey=""
    private var turnResolvedAt=0L
    private var turnLookupPending=false
    private var turnLookupGeneration=0L
    private var turnLookupGate="IDLE"
    private val travelCourse=TravelCourseMemory()
    private val notificationRoute=NotificationRouteTracker()
    private var travelCourseSource:String?=null
    private val turnLookupRetry=Runnable { if(!destroyed && !stopping)updateTurnGeometry() }
    private var lastArtGeometryKey:String?=null
    private lateinit var musicDetails: MediaArtworkLoader
    private var musicRequest:Closeable?=null
    private var musicUri:String?=null
    private lateinit var speaker:CueSpeaker
    private val cuePolicy=CuePolicy()
    private var cueActive=false
    private var resumeAfterCue=false
    private var spokenCues=0
    private var preparedRouteKey:String?=null
    private fun preparedRouteIdentity(snapshot:OwnNavigation.Snapshot):String? = snapshot.route?.let {
        "${System.identityHashCode(it.points.first())}:${System.identityHashCode(it.maneuvers.first())}"
    }
    private val ownObserver:(OwnNavigation.Snapshot)->Unit={snapshot->if(navigationWorkAllowed()){
        val routeKey=preparedRouteIdentity(snapshot)
        if(snapshot.routing || snapshot.progress?.offRoute==true || snapshot.progress?.arrived==true || routeKey!=preparedRouteKey){
            renderer.clearPrepared();preparedRouteKey=routeKey
        }
        configureLocation();updateTurnGeometry()
    }}
    private val messageObserver:(String)->Unit={text->if(navigationWorkAllowed() && Library.speakMessages(this))speaker.speak(text)}
    private var focus: AudioFocusRequest? = null
    private var focusHeld = false
    private var resumeAfterFocus = false
    private var started = false
    private var destroyed = false
    private var stopping = false
    private var resourcesCreated = false
    private val rejectedStart = org.openandroidauto.integration.RejectedForegroundStart()
    private var pendingArtworkAt:Long?=null
    private val artworkRetry=Runnable { if(!destroyed && !stopping)refreshArtwork(false) }
    private var locationIntervalMillis=0L
    private var lastArtAt = 0L
    private var lastArtGuidance: Guidance? = null
    private var artGeneration = 0L
    private var metadataGeneration = 0L
    private var demoStep = 0
    private var lastMetadataKey = ""
    private var lastNotificationKey = ""
    private var locationRegistered = false
    private var foregroundTypes = 0
    private var stereoGuidance: Guidance? = null
    private var stereoArtwork: Bitmap? = null
    private var navigationArtworkActive = false
    private val positionExpiry = Runnable {
        val point=AppState.position
        if(navigationWorkAllowed() && !AppState.demo && point!=null && SystemClock.elapsedRealtime()-point.receivedAt>=30_000) {
            AppState.position=null;travelCourse.reset();refreshArtwork(true)
        }
    }
    private val guidanceObserver: (Guidance?) -> Unit = guidance@{
        if (!AppState.demo && !stopping && !destroyed) {
            if (!navigationWorkAllowed()) {
                lastObservedGuidance = it
                AppState.guidance = it
                return@guidance
            }
            // GuidanceHub publishes its public state before notifying observers.
            // Retain our previous event independently so maneuver transitions are detected.
            val old=lastObservedGuidance
            val newTurn=GuidanceChangePolicy.startsNewTurn(old,it)
            lastObservedGuidance=it
            if(it?.source!="Google Maps" || Regex("recalculat|calculat|rerout|off[ -]route",RegexOption.IGNORE_CASE).containsMatchIn(it.instruction) ||
                newTurn && old?.instruction==it.instruction)notificationRoute.reset()
            if(newTurn){
                val own=OwnNavigation.snapshot
                val ownRouteContinues=it?.source=="Own maps" && OwnNavigation.active &&
                    !own.routing && own.progress?.offRoute==false && own.progress?.arrived==false &&
                    preparedRouteKey!=null && preparedRouteIdentity(own)==preparedRouteKey
                val keepPrepared=GuidanceChangePolicy.shouldKeepPrepared(old,it,ownRouteContinues)
                clearTurnGeometry();renderer.resetTurn(keepPrepared=keepPrepared)
            }
            AppState.guidance = it
            if(it==null) {AppState.position=null;travelCourse.reset();travelCourseSource=null;handler.removeCallbacks(positionExpiry)}
            configureLocation();speakGuidance(it)
            updateTurnGeometry();refreshArtwork(newTurn)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // An old notification/media intent must never create a competing VLC owner.
        if (rejectedStart.shouldReject(!org.openandroidauto.integration.ProjectionIntegration.isSessionActive() &&
            !org.bluetoothauto.integration.CarDisplayController.isSwitching())) {
            rejectedStart.stop(this, 64, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                "LibreAuto · Mode changed", R.drawable.ic_notification)
            return
        }
        audio = getSystemService(AudioManager::class.java)
        locations = getSystemService(LocationManager::class.java)
        renderer = MapArtworkRenderer(applicationContext)
        musicDetails=MediaArtworkLoader(this)
        turnResolver=TurnGeometryResolver(this)
        OwnNavigation.initialize(this)
        speaker=CueSpeaker(this,{busy->
            cueActive=busy
            if(busy){resumeAfterCue=player.playRequested;player.pause();spokenCues++}
            else if(resumeAfterCue){resumeAfterCue=false;if(!destroyed && focusHeld)player.play()}
        },{AppState.error="Spoken cues are unavailable. Check the phone’s text-to-speech settings.";AppState.changed()})
        session = MediaSession(this,"LibreAuto").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setSessionActivity(openApp())
            setMediaButtonReceiver(PendingIntent.getBroadcast(this@BluetoothAutoService,17,
                Intent(this@BluetoothAutoService,MediaButtonReceiver::class.java).setAction(Intent.ACTION_MEDIA_BUTTON),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            setCallback(object: MediaSession.Callback() {
                override fun onPlay() { playWithFocus() }
                override fun onPause() { userPause() }
                override fun onStop() { stopSession() }
                override fun onSkipToNext() { player.next() }
                override fun onSkipToPrevious() { player.previous() }
                override fun onSeekTo(pos:Long) { player.seekTo(pos) }
            },handler)
            isActive = true
        }
        player = VlcPlayer(this) { state ->
            AppState.playback = state
            AppState.error = state.error
            if (::player.isInitialized && !player.playRequested && !resumeAfterFocus && !cueActive) abandonFocus()
            loadMusicDetails(state.track)
            publishMetadata()
            AppState.changed()
        }
        player.setShuffle(Library.shuffle(this))
        GuidanceHub.observers.add(guidanceObserver)
        OwnNavigation.observers.add(ownObserver)
        MessageSpeechHub.observers.add(messageObserver)
        AppState.running = true
        resourcesCreated = true
        AppState.guidance = GuidanceHub.current
        lastObservedGuidance=GuidanceHub.current
        AppState.error = null
        AppState.changed()
        onOfficialProjectionChanged(OfficialProjectionMonitor.cacheActive())
        officialProjectionMonitor = OfficialProjectionMonitor.start(this, ::onOfficialProjectionChanged)
    }

    /** The normal media session survives; only the stereo navigation augmentation is suspended. */
    private fun onOfficialProjectionChanged(connected: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { onOfficialProjectionChanged(connected) }
            return
        }
        if (destroyed || stopping || !resourcesCreated) return
        when (officialProjectionWork.observeConnection(connected)) {
            OfficialProjectionWorkPolicy.Change.NONE -> return
            OfficialProjectionWorkPolicy.Change.SUSPEND_NAVIGATION -> {
                artGeneration++; cancelPendingArtwork(); renderer.cancelPending()
                clearTurnGeometry()
                handler.removeCallbacks(positionExpiry)
                if (locationRegistered) {
                    try { locations.removeUpdates(this) } catch (_: SecurityException) { }
                    locationRegistered = false; locationIntervalMillis = 0
                }
                val resumeMusic = resumeAfterCue
                resumeAfterCue = false
                speaker.cancel(); cuePolicy.reset()
                if (resumeMusic && focusHeld) player.play()
                stereoGuidance = null; navigationArtworkActive = false
                useMusicArtwork(); metadataGeneration++
                AppState.artworkStatus = "Music artwork · Android Auto handles navigation"
                if (started) configureLocation()
                publishMetadata(); AppState.changed()
            }
            OfficialProjectionWorkPolicy.Change.RESUME_NAVIGATION -> {
                val routeKey = preparedRouteIdentity(OwnNavigation.snapshot)
                if (routeKey != preparedRouteKey) { renderer.clearPrepared(); preparedRouteKey = routeKey }
                cuePolicy.reset()
                if (started) { configureLocation(); updateTurnGeometry(); refreshArtwork(true) }
            }
        }
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if (!resourcesCreated) {
            rejectedStart.stop(this, 64, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                "LibreAuto · Mode changed", R.drawable.ic_notification, startId=startId)
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            if(intent?.action !in listOf(STOP,DEMO,DEMO_NEXT)) { configureLocation();refreshArtwork(true) }
        }
        when(intent?.action) {
            STOP -> { stopSession(); return START_NOT_STICKY }
            DEMO -> startDemo()
            DEMO_NEXT -> { if (AppState.demo) { demoStep++; demoGuidance() } else startDemo() }
            LIVE -> {
                notificationRoute.reset()
                AppState.demo = false; demoStep = 0; AppState.position = null;travelCourse.reset();travelCourseSource=null;renderer.resetTurn();clearTurnGeometry()
                AppState.guidance = GuidanceHub.current
                lastObservedGuidance=AppState.guidance
                cuePolicy.reset();speaker.cancel()
                configureLocation();updateTurnGeometry();refreshArtwork(true)
            }
            PLAY_INDEX -> {
                resumeAfterCue=false;speaker.cancel()
                val tracks = Library.tracks(this)
                if (tracks.isNotEmpty() && requestFocus()) {
                    player.setShuffle(Library.shuffle(this))
                    player.setQueue(tracks,intent.getIntExtra("index",0).coerceIn(tracks.indices),true)
                }
                configureLocation(); refreshArtwork(true)
            }
            TOGGLE -> { if (player.state.playing || player.playRequested) userPause() else playWithFocus() }
            PLAY -> playWithFocus()
            PAUSE -> userPause()
            NEXT -> player.next()
            PREVIOUS -> player.previous()
            SHUFFLE -> player.setShuffle(Library.shuffle(this))
            QUEUE_CHANGED -> {
                val tracks=Library.tracks(this)
                if(tracks.isEmpty())userPause()
                player.updateQueue(tracks);player.setShuffle(Library.shuffle(this))
            }
            SEEK -> player.seekTo(intent.getLongExtra("position",0))
            SETTINGS -> { notificationRoute.reset();speaker.cancel();cuePolicy.reset();renderer.resetTurn();clearTurnGeometry();configureLocation();updateTurnGeometry(); refreshArtwork(true) }
            PREPARED_MAPS_CHANGED -> {
                renderer.resetTurn();turnResolver.clear(keepGraph=false);clearTurnGeometry()
                configureLocation();updateTurnGeometry();refreshArtwork(true)
            }
            HINTS -> { notificationRoute.reset();clearTurnGeometry();configureLocation();updateTurnGeometry();refreshArtwork(true) }
            START -> { configureLocation();refreshArtwork(true) }
        }
        return START_NOT_STICKY
    }

    private fun requestFocus(): Boolean {
        if (focusHeld) return true
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when(change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusHeld = false; resumeAfterFocus = false;resumeAfterCue=false;speaker.cancel(); player.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeAfterFocus = player.playRequested || resumeAfterCue; focusHeld = false;resumeAfterCue=false;speaker.cancel(); player.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.setVolume(22)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusHeld = true; player.setVolume(100)
                        if (resumeAfterFocus) { resumeAfterFocus = false; player.play() }
                    }
                }
            },handler).build()
        focus = req
        focusHeld = audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if(focusHeld)player.setVolume(100)
        if (!focusHeld) { AppState.error = "Audio is busy. Try Play again."; AppState.changed() }
        return focusHeld
    }
    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null; focusHeld = false
    }
    private fun userPause() {
        resumeAfterFocus=false;resumeAfterCue=false;speaker.cancel();player.pause();abandonFocus()
    }
    private fun playWithFocus() {
        resumeAfterCue=false;speaker.cancel()
        if (player.state.track == null) {
            val tracks = Library.tracks(this)
            if (tracks.isNotEmpty() && requestFocus()) player.setQueue(tracks,0,true)
            else { AppState.error = "Add music to start playback."; AppState.changed() }
        } else if (requestFocus()) player.play()
    }

    private fun configureLocation() {
        if(destroyed || stopping)return
        if (!officialProjectionWork.navigationAllowed) {
            if (locationRegistered) {
                try { locations.removeUpdates(this) } catch (_: SecurityException) { }
                locationRegistered = false; locationIntervalMillis = 0
            }
            foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            return
        }
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Establish the location service type while the user starts the session on screen.
        // Actual fixes are requested only while Maps guidance is present.
        val ownTrip=Library.navigator(this)=="OPEN" && OwnNavigation.active
        val placement=Library.hintPlacement(this)
        val locationSession = !AppState.demo && (placement.artworkLocation(Library.mapEnabled(this)) || ownTrip) && hasLocation
        val needsLocation = locationSession && placement.needsLocation(Library.mapEnabled(this),AppState.guidance,ownTrip)
        try {
            if (locationSession) {
                foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else foreground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            if (needsLocation) {
                val requestedInterval=DistanceRefreshPolicy.locationIntervalMillis(AppState.guidance?.distanceMeters)
                if(locationRegistered && locationIntervalMillis==requestedInterval)return
                if(locationRegistered){locations.removeUpdates(this);locationRegistered=false;locationIntervalMillis=0}
                var registered=false
                for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    if (locations.isProviderEnabled(provider)) {
                        locations.requestLocationUpdates(provider,requestedInterval,0f,this,Looper.getMainLooper());registered=true
                    }
                }
                locationRegistered=registered;locationIntervalMillis=if(registered)requestedInterval else 0
            } else if(locationRegistered) {locations.removeUpdates(this);locationRegistered=false;locationIntervalMillis=0}
        } catch (_: SecurityException) {
            AppState.error = "Location access is unavailable. Turn guidance still works."
        } catch (_: IllegalArgumentException) { AppState.error = "Phone location is unavailable." }
    }

    override fun onLocationChanged(location:Location) {
        if (AppState.demo || !navigationWorkAllowed()) return
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (age !in 0..30_000_000_000L || !location.latitude.isFinite() || !location.longitude.isFinite()) return
        if (location.hasAccuracy() && location.accuracy > 100f) return
        val recordedAt=location.elapsedRealtimeNanos/1_000_000L
        val previous=AppState.position
        if(previous!=null && !previous.demo && (recordedAt<previous.receivedAt ||
                (recordedAt==previous.receivedAt && location.accuracy>=previous.accuracy)))return
        val courseSource="${Library.navigator(this)}|${AppState.guidance?.source}"
        if(courseSource!=travelCourseSource){travelCourse.reset();travelCourseSource=courseSource}
        val raw=Position(location.latitude,location.longitude,
            if(location.hasBearing() && location.bearing.isFinite()) location.bearing else null,
            if(location.hasAccuracy())location.accuracy else 0f,receivedAt=recordedAt,
            speedMps=if(location.hasSpeed() && location.speed.isFinite() && location.speed>=0)location.speed else null)
        // A simulator explicitly supplies course even while parked. Real GPS uses recent travel,
        // never the orientation of a phone sitting on a seat or noisy stationary GPS bearings.
        @Suppress("DEPRECATION")
        val mock=if(Build.VERSION.SDK_INT>=31)location.isMock else location.isFromMockProvider
        val trustedMockCourse=mock && location.hasBearingAccuracy() &&
            location.bearingAccuracyDegrees.isFinite() && location.bearingAccuracyDegrees in 0f..15f
        AppState.position=travelCourse.update(raw,trustedStationaryCourse=trustedMockCourse)
        handler.removeCallbacks(positionExpiry);handler.postDelayed(positionExpiry,(31_000-age/1_000_000L).coerceAtLeast(1))
        if(Library.navigator(this)=="OPEN")OwnNavigation.updatePosition(applicationContext,AppState.position!!)
        updateTurnGeometry();refreshArtwork(false)
    }
    override fun onProviderEnabled(provider:String) { locations.removeUpdates(this);locationRegistered=false;locationIntervalMillis=0;configureLocation() }
    override fun onProviderDisabled(provider:String) {
        locations.removeUpdates(this);locationRegistered=false;locationIntervalMillis=0;AppState.position=null;travelCourse.reset()
        configureLocation();refreshArtwork(true)
    }
    @Deprecated("Legacy location callback") override fun onStatusChanged(provider:String?, status:Int,extras:Bundle?) {}

    private fun clearTurnGeometry(){
        turnLookupGeneration++;handler.removeCallbacks(turnLookupRetry);turnLookupPending=false;turnLookupGate="IDLE";turnResolvedAt=0
        turnRequest?.close();turnRequest=null;turnResolver.clear(keepGraph=true);turnRequestKey=""
        AppState.turnGeometry=null
    }
    private fun updateTurnGeometry(){
        if(!navigationWorkAllowed() || AppState.demo)return
        if(!Library.mapEnabled(this) || !Library.hintPlacement(this).inArtwork){clearTurnGeometry();return}
        if(Library.navigator(this)=="OPEN"){
            notificationRoute.reset()
            AppState.turnGeometry=OwnTurnGeometry.from(OwnNavigation.snapshot)
            return
        }
        val g=AppState.guidance
        if(g==null){notificationRoute.reset();clearTurnGeometry();return}
        val actual=AppState.position
        val p=actual?.let{point->
            notificationRoute.fallbackCourse(point,g,SystemClock.elapsedRealtime())?.let{point.copy(bearing=it.toFloat())} ?: point
        }
        val key="${g.source}|${g.instruction}"
        if(key!=turnRequestKey){clearTurnGeometry();turnRequestKey=key;turnResolvedAt=0}
        // A confirmed junction is a fixed preview of this maneuver, not a moving map.
        if(AppState.turnGeometry!=null){turnLookupGate="CONFIRMED";handler.removeCallbacks(turnLookupRetry);return}
        turnLookupGate=when {
            p==null || !p.accuracy.isFinite() || p.accuracy !in 0f..25f || SystemClock.elapsedRealtime()-p.receivedAt !in 0..15_000 -> "WAITING_FIX"
            p.bearing==null || !p.bearing.isFinite() -> "WAITING_COURSE"
            g.distanceMeters==null || g.distanceMeters !in 5.0..50_000.0 -> "WAITING_DISTANCE"
            !NotificationRouteResolver.supports(g.instruction,g.distanceMeters) -> "UNSUPPORTED_INSTRUCTION"
            else -> "READY"
        }
        if(turnLookupGate!="READY")return
        if(turnLookupPending){turnLookupGate="PENDING";return}
        val now=SystemClock.elapsedRealtime()
        // Cached graph matching is cheap. Only the resolver throttles actual network requests.
        if(turnResolvedAt!=0L && now-turnResolvedAt<3_000)return
        turnResolvedAt=now
        handler.removeCallbacks(turnLookupRetry);turnLookupPending=true;turnLookupGate="PENDING"
        val generation=turnLookupGeneration
        turnRequest=turnResolver.resolve(checkNotNull(p),g){geometry->
            if(generation==turnLookupGeneration && navigationWorkAllowed() && !AppState.demo && Library.hintPlacement(this).inArtwork && Library.mapEnabled(this) && Library.navigator(this)=="GOOGLE" && AppState.guidance?.let{"${it.source}|${it.instruction}"}==key){
                turnLookupPending=false;turnRequest=null;turnLookupGate=if(geometry!=null)"CONFIRMED" else "UNRESOLVED"
                AppState.turnGeometry=geometry
                if(geometry!=null)notificationRoute.remember(g,geometry,SystemClock.elapsedRealtime())
                // A failed/throttled road lookup has not changed the already-published card.
                if(geometry!=null)refreshArtwork(true)
                if(geometry==null)handler.postDelayed(turnLookupRetry,30_000)
            }
        }
    }
    private fun cancelPendingArtwork(){
        handler.removeCallbacks(artworkRetry);pendingArtworkAt=null
    }
    private fun stopSession(){stopping=true;artGeneration++;cancelPendingArtwork();clearTurnGeometry();travelCourse.reset();notificationRoute.reset();resumeAfterCue=false;speaker.cancel();stopSelf()}
    private fun refreshArtwork(force:Boolean) {
        if (!navigationWorkAllowed()) return
        val now = SystemClock.elapsedRealtime()
        if(AppState.demo)AppState.position=AppState.position?.copy(receivedAt=now)
        val placement=Library.hintPlacement(this)
        val position = if (placement.artworkLocation(Library.mapEnabled(this))) AppState.position ?:
            AppState.turnGeometry?.junction?.let{Position(it.latitude,it.longitude,accuracy=0f,receivedAt=now)} else null
        val guidance = AppState.guidance
        val geometryKey=AppState.turnGeometry?.key
        val geometryChanged=geometryKey!=lastArtGeometryKey
        val instructionChanged = geometryChanged || guidance?.instruction != lastArtGuidance?.instruction || guidance?.source != lastArtGuidance?.source
        val units=Library.units(this)
        fun distance(g:Guidance?)=g?.let{GuidanceFormatter.formatDistance(it.distanceMeters,it.distanceLabel,units)}
        val distanceChanged = distance(guidance) != distance(lastArtGuidance)
        val mapOpportunity=Library.mapEnabled(this) && placement.navigationArtwork(guidance) &&
            renderer.needsTurnMap(position,guidance,AppState.turnGeometry)
        val due=DistanceRefreshPolicy.dueAtMillis(now,lastArtAt,guidance?.distanceMeters,
            hasChanges=distanceChanged,
            immediate=force || instructionChanged || mapOpportunity,existingDueAt=pendingArtworkAt)
        if(due==null){cancelPendingArtwork();return}
        if(due>now){
            if(pendingArtworkAt!=due){
                handler.removeCallbacks(artworkRetry);pendingArtworkAt=due
                handler.postDelayed(artworkRetry,(due-SystemClock.elapsedRealtime()).coerceAtLeast(1))
            }
            return
        }
        cancelPendingArtwork()
        lastArtAt = now; lastArtGuidance = guidance;lastArtGeometryKey=geometryKey
        stereoGuidance=guidance
        val token = ++artGeneration
        if(!placement.navigationArtwork(guidance)) {
            if(navigationArtworkActive){renderer.cancelPending();navigationArtworkActive=false}
            useMusicArtwork()
            publishMetadata();AppState.changed();return
        }
        navigationArtworkActive=true
        renderer.render(position,guidance,Library.units(this),AppState.turnGeometry) { bitmap,status ->
            if (!navigationWorkAllowed() || token != artGeneration) return@render
            AppState.artwork = bitmap; AppState.artworkStatus = status
            stereoArtwork=if(guidance!=null)Bitmap.createScaledBitmap(bitmap,256,256,true)
                else AppState.presentation?.artwork?.let{Bitmap.createScaledBitmap(it,256,256,true)}
            metadataGeneration++
            publishMetadata(); AppState.changed()
            if(status=="Upcoming turn")prepareUpcomingTurns()
        }
        publishMetadata(); AppState.changed()
    }

    private fun prepareUpcomingTurns(){
        if(!navigationWorkAllowed() || AppState.demo ||
            !Library.mapEnabled(this) || !Library.hintPlacement(this).inArtwork)return
        val units=Library.units(this)
        if(Library.navigator(this)=="GOOGLE")return
        UpcomingTurnPlanner.plan(OwnNavigation.snapshot).filter{it.geometry.key!=AppState.turnGeometry?.key}.take(2).forEach{
            renderer.prepare(it.position,it.guidance,units,it.geometry)
        }
    }

    private fun loadMusicDetails(track:Track?) {
        if(track?.uri==musicUri)return
        musicUri=track?.uri;musicRequest?.close();musicRequest=null;AppState.presentation=null
        if(!officialProjectionWork.navigationAllowed || !Library.hintPlacement(this).navigationArtwork(AppState.guidance))useMusicArtwork()
        if(track!=null)musicRequest=musicDetails.load(track){details->
            if(!destroyed && !stopping && AppState.playback.track?.uri==details.uri){
                AppState.presentation=details
                if(!officialProjectionWork.navigationAllowed || !Library.hintPlacement(this).navigationArtwork(AppState.guidance))useMusicArtwork()
                publishMetadata();AppState.changed()
            }
        }
    }
    private fun useMusicArtwork() {
        val cover=AppState.presentation?.takeIf{it.uri==AppState.playback.track?.uri}?.artwork
        if(AppState.artwork!==cover || (cover==null && stereoArtwork!=null)) {
            AppState.artwork=cover
            stereoArtwork=cover?.let{Bitmap.createScaledBitmap(it,256,256,true)}
            metadataGeneration++
        }
        AppState.artworkStatus=if(cover!=null)"Album artwork" else "Original record sleeve"
    }
    private fun speakGuidance(guidance:Guidance?) {
        if(!navigationWorkAllowed())return
        if(guidance==null){cuePolicy.reset();return}
        if(!Library.speakDirections(this))return
        if(cuePolicy.accept(guidance.instruction)){
            val distance=GuidanceFormatter.formatDistance(guidance.distanceMeters,guidance.distanceLabel,Library.units(this))
            speaker.speak(if(distance.isBlank())guidance.instruction else "In $distance. ${guidance.instruction}")
        }
    }
    private fun startDemo() {
        if(!navigationWorkAllowed())return
        notificationRoute.reset()
        cancelPendingArtwork()
        OwnNavigation.stop();clearTurnGeometry();travelCourse.reset();travelCourseSource=null;cuePolicy.reset();AppState.demo = true; demoStep = 0
        locations.removeUpdates(this);locationRegistered=false;locationIntervalMillis=0
        val sample = File(filesDir,"BluetoothAuto-demo.wav")
        if (!sample.exists()) assets.open("demo.wav").use { input -> sample.outputStream().use { input.copyTo(it) } }
        if (requestFocus()) player.setQueue(List(20){Track(Uri.fromFile(sample).toString(),"Morning drive · demo","LibreAuto")},0,true)
        demoGuidance()
    }
    private fun demoGuidance() {
        AppState.position = Position(47.6062,-122.3321,0f,demo=true)
        AppState.guidance = when(demoStep % 4) {
            0 -> Guidance("Turn right onto James Street",480.0,"480 m",source="Demo",demo=true)
            1 -> Guidance("Turn left onto 2nd Avenue",160.0,"160 m",source="Demo",demo=true)
            2 -> Guidance("Continue straight",1609.344,"1 mi",source="Demo",demo=true)
            else -> Guidance("You have arrived",0.0,"",source="Demo",demo=true)
        }
        refreshArtwork(true);speakGuidance(AppState.guidance)
    }

    private fun publishMetadata() {
        if (destroyed || stopping || !::session.isInitialized) return
        val state = AppState.playback
        val presentation=AppState.presentation?.takeIf{it.uri==state.track?.uri}
        val artist=presentation?.artist?.takeIf{it.isNotBlank()} ?: state.track?.artist ?: "LibreAuto"
        val placement=Library.hintPlacement(this)
        val titleGuidance = if (officialProjectionWork.navigationAllowed) placement.titleGuidance(stereoGuidance) else null
        val title = GuidanceFormatter.title(titleGuidance,presentation?.title ?: state.track?.title ?: "LibreAuto",Library.units(this))
        AppState.metadataTitle = title
        val album=presentation?.album ?: "LibreAuto"
        val metadataKey = listOf(title,artist,album,state.durationMs,state.queueIndex,metadataGeneration,placement.name).joinToString("|")
        if(metadataKey != lastMetadataKey) {
        lastMetadataKey=metadataKey
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM,album)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,"${state.queueIndex}:$metadataGeneration")
            .putLong(MediaMetadata.METADATA_KEY_DURATION,state.durationMs)
        stereoArtwork?.let {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART,it)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,it)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON,it)
        }
        session.setMetadata(metadata.build())
        }
        val actions = AndroidPlaybackState.ACTION_PLAY or AndroidPlaybackState.ACTION_PAUSE or
            AndroidPlaybackState.ACTION_PLAY_PAUSE or AndroidPlaybackState.ACTION_SKIP_TO_NEXT or
            AndroidPlaybackState.ACTION_SKIP_TO_PREVIOUS or AndroidPlaybackState.ACTION_STOP or AndroidPlaybackState.ACTION_SEEK_TO
        session.setPlaybackState(AndroidPlaybackState.Builder().setActions(actions)
            .setState(if(state.playing)AndroidPlaybackState.STATE_PLAYING else AndroidPlaybackState.STATE_PAUSED,
                state.positionMs,if(state.playing)1f else 0f).build())
        val notificationKey = "$metadataKey|${state.playing}"
        if (started && notificationKey != lastNotificationKey) {
            lastNotificationKey=notificationKey
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification())
        }
    }

    private fun openApp() = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun foreground(types:Int) {
        if(foregroundTypes==types)return
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION_ID,notification(),types)
        else startForeground(NOTIFICATION_ID,notification())
        foregroundTypes=types
    }
    private fun actionIntent(action:String,code:Int) = PendingIntent.getService(this,code,Intent(this,BluetoothAutoService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification():Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"Music and navigation",NotificationManager.IMPORTANCE_LOW).apply {
            description = "Playback controls while a LibreAuto session is running"; setSound(null,null)
        })
        val b = Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(AppState.metadataTitle).setContentText(AppState.playback.track?.artist ?: "Ready for music")
            .setContentIntent(openApp()).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(AppState.playback.playing).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous,"Previous",actionIntent(PREVIOUS,1)).build())
            .addAction(Notification.Action.Builder(if(AppState.playback.playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if(AppState.playback.playing)"Pause" else "Play",actionIntent(TOGGLE,2)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next,"Next",actionIntent(NEXT,3)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop session",actionIntent(STOP,4)).build())
        AppState.artwork?.let { b.setLargeIcon(it) }
        if (::session.isInitialized) b.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0,1,2))
        return b.build()
    }

    override fun onBind(intent:Intent?):IBinder? = null
    override fun dump(fd:FileDescriptor,writer:PrintWriter,args:Array<out String>) {
        writer.println("LibreAuto diagnostic (no coordinates or notification text)")
        writer.println("running=${AppState.running} demo=${AppState.demo} playing=${AppState.playback.playing}")
        writer.println("official_aa_active=${officialProjectionWork.connected} stereo_navigation_suspended=${!officialProjectionWork.navigationAllowed} official_state_known=${OfficialProjectionMonitor.cacheKnown()}")
        writer.println("position_ms=${AppState.playback.positionMs} duration_ms=${AppState.playback.durationMs} queue_index=${AppState.playback.queueIndex} queue_size=${AppState.playback.queueSize}")
        writer.println("guidance_present=${AppState.guidance!=null} maps_listener=${AppState.listenerConnected} location_registered=$locationRegistered")
        writer.println("artwork=${AppState.artwork?.width ?: 0}x${AppState.artwork?.height ?: 0} stereo_artwork=${stereoArtwork?.width ?: 0}x${stereoArtwork?.height ?: 0} revision=$metadataGeneration")
        writer.println("map_status=${AppState.artworkStatus} error_present=${AppState.error!=null}")
        val work=renderer.diagnostics()
        writer.println("distance_interval_ms=${DistanceRefreshPolicy.intervalMillis(AppState.guidance?.distanceMeters)} pending_artwork=${pendingArtworkAt!=null} location_interval_ms=$locationIntervalMillis")
        writer.println("map_snapshots=${work.snapshotRequests} artwork_compositions=${work.compositionCount} cached_map_hits=${work.cacheHits} static_layer_builds=${work.staticLayerBuilds}")
        writer.println("map_native_route_snapshots=${work.nativeRouteSnapshots}")
        writer.println("map_snapshot_last_ms=${work.lastSnapshotMs} map_snapshot_max_ms=${work.maxSnapshotMs} map_snapshot_total_ms=${work.totalSnapshotMs} map_snapshots_completed=${work.completedSnapshotCount} map_prepared_adopt_ms=${work.lastPreparedAdoptMs}")
        writer.println("prepared_map_hits=${work.preparedCacheHits} prepared_maps=${work.preparedMapCount} prefetch_snapshots=${work.prefetchSnapshotRequests} preparing_map=${work.isPreparing}")
        writer.println("navigator=${Library.navigator(this)} own_trip=${OwnNavigation.active} audio_mode=${Library.audioMode(this)} cue_active=$cueActive spoken_cues=$spokenCues presentation_loaded=${AppState.presentation!=null}")
        writer.println("hint_placement=${Library.hintPlacement(this)} navigation_artwork=$navigationArtworkActive")
        writer.println("turn_geometry=${AppState.turnGeometry?.confidence ?: "none"} turn_points=${AppState.turnGeometry?.points?.size ?: 0}")
        val lookup=turnResolver.diagnostics()
        writer.println("turn_lookup_gate=$turnLookupGate turn_lookup_pending=$turnLookupPending course_available=${AppState.position?.bearing!=null} turn_lookup_status=${lookup.status}")
        writer.println("turn_network_attempts=${lookup.networkAttempts} turn_cache_hits=${lookup.cacheHits} turn_matches=${lookup.matches} turn_failures=${lookup.failures} turn_elapsed_ms=${lookup.lastElapsedMillis} turn_cached_nodes=${lookup.cachedNodes} turn_cached_ways=${lookup.cachedWays}")
        writer.println("turn_last_failure=${lookup.lastFailureStatus} turn_consecutive_failures=${lookup.consecutiveNetworkFailures} turn_network_retry_ms=${lookup.networkRetryInMillis}")
        writer.println("turn_candidate_status=${lookup.candidateStatus} turn_candidate_network_requests=${lookup.candidateNetworkRequests} turn_candidate_cache_hits=${lookup.candidateCacheHits}")
        writer.println("turn_saved_area_hits=${lookup.persistentCacheHits} turn_graph_source=${lookup.graphSource} turn_saved_areas=${lookup.preparedAreaCount} turn_store_failures=${lookup.persistentStoreFailures}")
    }
    override fun onDestroy() {
        if (!resourcesCreated) { super.onDestroy(); return }
        destroyed = true; artGeneration++;cancelPendingArtwork()
        officialProjectionMonitor?.close(); officialProjectionMonitor = null
        GuidanceHub.observers.remove(guidanceObserver)
        OwnNavigation.observers.remove(ownObserver);MessageSpeechHub.observers.remove(messageObserver)
        OwnNavigation.stop();clearTurnGeometry();turnResolver.close();resumeAfterCue=false;speaker.close();musicRequest?.close();musicDetails.close()
        locations.removeUpdates(this)
        player.close(); renderer.close(); session.isActive = false; session.release()
        abandonFocus(); handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppState.running = false; AppState.demo = false; AppState.position = null
        AppState.playback = PlaybackState();AppState.presentation=null;AppState.turnGeometry=null; AppState.artwork = null
        AppState.artworkStatus = "Session stopped"; AppState.metadataTitle = "LibreAuto"
        AppState.guidance = GuidanceHub.current; AppState.changed()
        super.onDestroy()
    }
    companion object {
        const val START = "org.bluetoothauto.START"
        const val STOP = "org.bluetoothauto.STOP"
        const val DEMO = "org.bluetoothauto.DEMO"
        const val DEMO_NEXT = "org.bluetoothauto.DEMO_NEXT"
        const val LIVE = "org.bluetoothauto.LIVE"
        const val PLAY_INDEX = "org.bluetoothauto.PLAY_INDEX"
        const val TOGGLE = "org.bluetoothauto.TOGGLE"
        const val PLAY = "org.bluetoothauto.PLAY"
        const val PAUSE = "org.bluetoothauto.PAUSE"
        const val NEXT = "org.bluetoothauto.NEXT"
        const val PREVIOUS = "org.bluetoothauto.PREVIOUS"
        const val SHUFFLE = "org.bluetoothauto.SHUFFLE"
        const val QUEUE_CHANGED = "org.bluetoothauto.QUEUE_CHANGED"
        const val SETTINGS = "org.bluetoothauto.SETTINGS"
        const val PREPARED_MAPS_CHANGED = "org.bluetoothauto.PREPARED_MAPS_CHANGED"
        const val HINTS = "org.bluetoothauto.HINTS"
        const val SEEK = "org.bluetoothauto.SEEK"
        fun seek(context:Context,position:Long){
            if(org.bluetoothauto.integration.SharedLibraryBridge.routeCommand(context,SEEK,positionMs=position))return
            if(AppState.running)context.startService(Intent(context,BluetoothAutoService::class.java).setAction(SEEK).putExtra("position",position.coerceAtLeast(0)))
        }
        private const val CHANNEL = "bluetoothauto-playback"
        private const val NOTIFICATION_ID = 20
        fun command(context:Context,action:String,index:Int=0) {
            if(org.bluetoothauto.integration.CarDisplayController.isSwitching())return
            if(org.bluetoothauto.integration.SharedLibraryBridge.routeCommand(context,action,index))return
            if(org.openandroidauto.integration.ProjectionIntegration.isSessionActive()) {
                AppState.error="The car display owns playback. Use its controls or stop the connection in Settings."
                AppState.changed();return
            }
            val intent = Intent(context,BluetoothAutoService::class.java).setAction(action).putExtra("index",index)
            if(action==STOP && !AppState.running)return
            try {
                if(AppState.running)context.startService(intent) else context.startForegroundService(intent)
            } catch (_: RuntimeException) {
                AppState.error = "Open LibreAuto and start the session from its screen."
                AppState.changed()
            }
        }
    }
}
