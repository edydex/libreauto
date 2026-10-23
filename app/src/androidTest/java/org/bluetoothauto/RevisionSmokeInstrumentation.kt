// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import org.bluetoothauto.artwork.MapArtworkRenderer
import org.bluetoothauto.artwork.TurnArtworkPolicy
import org.bluetoothauto.model.PlaybackState
import org.bluetoothauto.model.Guidance
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import org.bluetoothauto.artwork.ManeuverIconView
import org.bluetoothauto.messaging.MessageNotificationParser
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.Track
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.*
import org.bluetoothauto.playback.MediaArtworkLoader
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshotter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Separate test APK. Default fixtures are public/synthetic; optional diagnostics use operator-supplied cache files. */
class RevisionSmokeInstrumentation:Instrumentation(){
    private var libreAutoMergeTest=false
    private var projectionIdentityCheck=false
    private var officialProjectionMonitorTest=false
    private var musicGrantCleanup:String?=null
    private var musicLibraryStoreTest=false
    private var musicFolderTree:String?=null
    private var ownRouteArtworkTest=false
    private var ownRouteBaselineTest=false
    private var englishManeuverTest=false
    private var ownRouteTiming:org.json.JSONObject?=null
    private var contextMapTest=false
    private var criticalDistanceTest=false
    private var retainedMusicTest=false
    private var liveGoogleTest=false
    private var privateTurnArtworkTest=false
    private var privateRouteLookupTest=false
    private var googleTurnListTest=false
    private var googleDirectionsCaptureTest=false
    private var preparedTurnArtworkTest=false
    private var routeTest=false
    private var googleGeometryTest=false
    private var approachUpTest=false
    private var cadenceTest=false
    private var placementTest=false
    private var musicTest=false
    private var placementReport="not-run"
    private var cadenceReport="not-run"
    private var cacheReport="not-run"
    private var approachUpReport="not-run"
    private var googleMatch="not-run"
    override fun onCreate(arguments:Bundle?){
        super.onCreate(arguments)
        libreAutoMergeTest=arguments?.getString("libreAutoMerge")=="true"
        projectionIdentityCheck=arguments?.getString("projectionIdentityCheck")=="true"
        officialProjectionMonitorTest=arguments?.getString("officialProjectionMonitor")=="true"
        musicGrantCleanup=arguments?.getString("musicGrantCleanup")
        musicLibraryStoreTest=arguments?.getString("musicLibraryStore")=="true"
        musicFolderTree=arguments?.getString("musicFolderTree")
        ownRouteArtworkTest=arguments?.getString("ownRouteArtwork")=="true"
        ownRouteBaselineTest=arguments?.getString("ownRouteBaseline")=="true"
        englishManeuverTest=arguments?.getString("englishManeuver")=="true"
        contextMapTest=arguments?.getString("contextMap")=="true"
        criticalDistanceTest=arguments?.getString("criticalDistance")=="true"
        retainedMusicTest=arguments?.getString("retainedMusic")=="true"
        liveGoogleTest=arguments?.getString("liveGoogle")=="true"
        privateTurnArtworkTest=arguments?.getString("privateTurnArtwork")=="true"
        privateRouteLookupTest=arguments?.getString("privateRouteLookup")=="true"
        googleTurnListTest=arguments?.getString("googleTurnList")=="true"
        googleDirectionsCaptureTest=arguments?.getString("googleDirectionsCapture")=="true"
        preparedTurnArtworkTest=arguments?.getString("preparedTurnArtwork")=="true"
        googleGeometryTest=arguments?.getString("googleGeometry")=="true"
        routeTest=arguments?.getString("route")=="true" || googleGeometryTest
        approachUpTest=arguments?.getString("approachUp")=="true"
        cadenceTest=arguments?.getString("cadence")=="true"
        placementTest=arguments?.getString("placement")=="true"
        musicTest=arguments?.getString("music")=="true"
        start()
    }
    override fun onStart(){
        val results=mutableListOf<String>()
        try {
            if(officialProjectionMonitorTest){
                val report=OfficialProjectionMonitorAcceptance.run(this)
                finish(0,report.apply{putString("result","PASS")})
                return
            }
            if(libreAutoMergeTest){
                val report=LibreAutoMergeAcceptance.run(this,projectionIdentityCheck)
                finish(0,report.apply{putString("result","PASS")})
                return
            }
            if(musicGrantCleanup!=null){
                val report=MusicTestGrantCleanup.run(this,checkNotNull(musicGrantCleanup))
                finish(0,Bundle().apply{putString("result","PASS");putString("music_grant_cleanup",report)})
                return
            }
            if(musicLibraryStoreTest){
                val report=MusicLibraryStoreAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("music_library_store",report)})
                return
            }
            if(musicFolderTree!=null){
                val report=MusicFolderBrowserAcceptance.run(this,checkNotNull(musicFolderTree))
                finish(0,Bundle().apply{putString("result","PASS");putString("music_folder",report)})
                return
            }
            if(ownRouteArtworkTest){
                val report=OwnRouteArtworkAcceptance.run(this,ownRouteBaselineTest)
                finish(0,Bundle().apply{putString("result","PASS");putString("own_route_artwork",report)})
                return
            }
            if(englishManeuverTest){
                val report=EnglishManeuverAcceptance.run(this)
                finish(0,report.apply{putString("result","PASS")})
                return
            }
            if(contextMapTest){
                val report=ContextMapAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("current_area",report)})
                return
            }
            if(criticalDistanceTest){
                check(android.os.Build.VERSION.SDK_INT >= 36){ "Native critical-distance acceptance needs API 36+" }
                fun notification(title:String)=Notification.Builder(targetContext,"native-distance")
                    .setSmallIcon(R.drawable.ic_notification).setCategory(Notification.CATEGORY_NAVIGATION)
                    .setOngoing(true).setContentTitle(title).setShortCriticalText("250 ft").build()
                val parser=GoogleMapsNotificationParser(targetContext)
                val guidance=checkNotNull(parser.parse(notification("1 mi · Turn left toward Main Street")))
                check(kotlin.math.abs(checkNotNull(guidance.distanceMeters)-76.2)<.001){ "Current distance not applied" }
                check(guidance.instruction=="Turn left toward Main Street"){ "Instruction changed" }
                check(parser.parse(notification("Rerouting"))?.distanceMeters==null){ "Status inherited a turn distance" }
                finish(0,Bundle().apply{putString("result","PASS");putString("critical_distance","current distance; original instruction; status cleared")})
                return
            }
            if(retainedMusicTest){
                val tracks=Library.tracks(targetContext)
                val grants=targetContext.contentResolver.persistedUriPermissions.filter{it.isReadPermission}
                check(tracks.isNotEmpty()){ "No retained music queue" }
                var reopened=0
                tracks.forEach{track->
                    val uri=Uri.parse(track.uri)
                    check(grants.any{it.uri==uri || android.provider.DocumentsContract.isTreeUri(it.uri) && uri.toString().startsWith(it.uri.toString())}){ "A retained track lacks its persisted read grant" }
                    val ok=runCatching{targetContext.contentResolver.openAssetFileDescriptor(uri,"r")?.use{true} ?: false}.getOrDefault(false)
                    check(ok){ "A retained track cannot be reopened" };reopened++
                }
                finish(0,Bundle().apply{putString("result","PASS");putInt("queue_entries",tracks.size);putInt("readable_original_tracks",reopened);putInt("persisted_read_grants",grants.size)})
                return
            }
            if(googleDirectionsCaptureTest){
                val report=GoogleDirectionsCaptureAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("google_directions_capture",report)})
                return
            }
            if(googleTurnListTest){
                val count=GoogleTurnListDiagnostic.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putInt("google_maps_nodes",count)})
                return
            }
            if(preparedTurnArtworkTest){
                val report=PreparedTurnArtworkAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("prepared_turn_artwork",report)})
                return
            }
            if(privateTurnArtworkTest){
                val report=PrivateTurnArtworkAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("private_turn_artwork",report)})
                return
            }
            if(privateRouteLookupTest){
                val report=PrivateRouteLookupAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("private_route_lookup",report)})
                return
            }
            if(liveGoogleTest){
                val report=LiveGoogleArtworkAcceptance.run(this)
                finish(0,Bundle().apply{putString("result","PASS");putString("live_google_artwork",report)})
                return
            }
            nativeMessages();results.add("native MessagingStyle parse/dedup/stale/package/secret")
            nativeTags();results.add("native audio tags/cover/loader cancellation")
            nativeGlyph();results.add("native maneuver Canvas render")
            if(musicTest){results.add(MusicQueueAcceptance.run(this, targetContext, context))}
            if(placementTest){nativeHintPlacement();results.add("native title/art/both switching; tagged cover/title; title-only skips renderer; preference/queue restoration")}
            if(cadenceTest){nativeAdaptiveCadence();nativeCachedDistanceArtwork();results.add("native service1s/2s/5s latest-pending cadence; unchanged/clear/turn/stop; MediaSession title; cached-map composition")}
            if(approachUpTest){nativeApproachUp();results.add("native MapLibre approach-up projection eight headings; left/right handedness")}
            if(routeTest){nativeRoute();results.add("public own-map route/guidance/progress/stop")}
            finish(0,Bundle().apply{putString("checks",results.joinToString("; "));putString("result","PASS");putString("google_osm_match",googleMatch);putString("approach_up_projection",approachUpReport);putString("adaptive_cadence",cadenceReport);putString("cached_distance_artwork",cacheReport);putString("hint_placement",placementReport)})
        }catch(e:Throwable){finish(1,Bundle().apply{putString("result","FAIL");putString("failure",e.javaClass.simpleName+": "+e.message);putString("checks",results.joinToString("; "))})}
    }
    private fun nativeMessages(){
        val now=System.currentTimeMillis()
        fun notification(secret:Boolean=false)=Notification.Builder(targetContext,"synthetic-test")
            .setSmallIcon(R.drawable.ic_notification).setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(if(secret)Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
            .setStyle(Notification.MessagingStyle(Person.Builder().setName("Test phone").build())
                .addMessage("Synthetic parked test",now,Person.Builder().setName("Test sender").build())).build()
        fun sbn(pkg:String="org.thoughtcrime.securesms",time:Long=now,secret:Boolean=false)=StatusBarNotification(pkg,pkg,77,"test",Process.myUid(),Process.myPid(),0,notification(secret),UserHandle.getUserHandleForUid(Process.myUid()),time)
        val parser=MessageNotificationParser()
        check(parser.parse(sbn(),now)?.message=="Synthetic parked test")
        check(parser.parse(sbn(),now)==null)
        check(MessageNotificationParser().parse(sbn(time=now-31_000),now)==null)
        check(MessageNotificationParser().parse(sbn(pkg="unrelated.app"),now)==null)
        check(MessageNotificationParser().parse(sbn(secret=true),now)==null)
    }
    private fun nativeTags(){
        val file=File(targetContext.cacheDir,"revision-tagged-fixture.m4a")
        context.assets.open("tagged-test.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
        var loader:MediaArtworkLoader?=null
        try{
            val done=CountDownLatch(1);var failure:Throwable?=null
            runOnMainSync{
                loader=MediaArtworkLoader(targetContext)
                loader!!.load(Track(Uri.fromFile(file).toString(),"filename fallback")){value->
                    try{check(value.title=="Evening drive");check(value.artist=="BluetoothAuto test music");check(value.artwork?.width==320);check(value.durationMs>20_000)}catch(e:Throwable){failure=e};done.countDown()
                }
            }
            check(done.await(10,TimeUnit.SECONDS)){"Native tag callback timed out"};failure?.let{throw it}
            var cancelledCallback=false
            runOnMainSync{loader!!.load(Track(Uri.fromFile(file).toString(),"fallback")){cancelledCallback=true}.close()}
            waitForIdleSync();check(!cancelledCallback)
        }finally{runOnMainSync{loader?.close()};file.delete()}
    }
    private fun nativeGlyph(){
        runOnMainSync{
            val view=ManeuverIconView(targetContext);view.setInstruction("Turn right onto Test Street");view.layout(0,0,200,200)
            val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            var nonEmpty=0;for(y in 0 until 200)for(x in 0 until 200)if(bitmap.getPixel(x,y) ushr 24!=0)nonEmpty++
            check(nonEmpty>1000);bitmap.recycle()
        }
    }
    /** Actual LibVLC session with original synthetic tagged audio; no location requests or network. */
    private fun nativeHintPlacement(){
        val prefs=targetContext.getSharedPreferences("bluetoothauto",Context.MODE_PRIVATE)
        val keys=setOf("navigator","map","audioMode","units","hintPlacement","tracks")
        val baseline=prefs.all.filterKeys{it in keys}
        val file=File(targetContext.cacheDir,"placement-tagged-${SystemClock.elapsedRealtimeNanos()}.m4a")
        var activity:android.app.Activity?=null
        var ownsFixture=false
        val key="NATIVE_HINT_PLACEMENT_FIXTURE"
        val guidance=Guidance("Turn right onto Placement Test Road",120.0,source="Placement test")
        val manager=targetContext.getSystemService(MediaSessionManager::class.java)
        val component=ComponentName(targetContext,MapsNotificationListener::class.java)
        fun await(label:String,timeout:Long=1500,condition:()->Boolean){
            val until=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<until){var ready=false;runOnMainSync{ready=condition()};if(ready)return;SystemClock.sleep(20)}
            error("Placement acceptance timed out: $label")
        }
        fun metadata():MediaMetadata?=manager.getActiveSessions(component).firstOrNull{it.packageName==targetContext.packageName}?.metadata
        fun dump():String=android.os.ParcelFileDescriptor.AutoCloseInputStream(
            uiAutomation.executeShellCommand("dumpsys activity service org.bluetoothauto/.BluetoothAutoService")
        ).bufferedReader().use{it.readText().take(32_768)}
        fun counters(text:String):List<Long>{
            val match=Regex("map_snapshots=(\\d+) artwork_compositions=(\\d+) cached_map_hits=(\\d+) static_layer_builds=(\\d+)").find(text)
                ?:error("Content-free artwork counters unavailable")
            return (1..4).map{match.groupValues[it].toLong()}
        }
        fun switch(mode:HintPlacement,expectedTitle:String,originalCover:Boolean){
            val startedAt=SystemClock.elapsedRealtime()
            runOnMainSync{Library.hintPlacement(targetContext,mode);BluetoothAutoService.command(targetContext,BluetoothAutoService.HINTS)}
            await("immediate $mode"){
                val art=AppState.artwork
                AppState.metadataTitle==expectedTitle && art!=null &&
                    if(originalCover)art===AppState.presentation?.artwork else art.width==512
            }
            check(SystemClock.elapsedRealtime()-startedAt<1000){"Placement change was delayed"}
            await("MediaSession $mode"){
                val value=metadata();val art=value?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                value?.getString(MediaMetadata.METADATA_KEY_TITLE)==expectedTitle && art!=null && art.width==256
            }
            check(Library.hintPlacement(targetContext)==mode && prefs.getString("hintPlacement",null)==mode.name){"Placement preference did not persist"}
            check(AppState.guidance?.instruction==guidance.instruction){"Placement change cleared live guidance"}
        }
        try{
            runOnMainSync{
                check(!AppState.running && GuidanceHub.current==null){"Stop existing navigation/playback before placement acceptance"}
                check(prefs.edit().putString("navigator","GOOGLE").putBoolean("map",false).putString("audioMode","OFF")
                    .putString("units","METRIC").putString("hintPlacement","BOTH").commit())
                ownsFixture=true
            }
            context.assets.open("tagged-test.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
            runOnMainSync{Library.save(targetContext,listOf(Track(Uri.fromFile(file).toString(),"Placement filename fallback")))}
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync{BluetoothAutoService.command(targetContext,BluetoothAutoService.PLAY_INDEX,0)}
            await("tagged audio playback",10_000){AppState.playback.playing && AppState.presentation?.title=="Evening drive" && AppState.presentation?.artwork?.width==320}
            runOnMainSync{GuidanceHub.publish(key,guidance)}
            val hinted=GuidanceFormatter.title(guidance,"Evening drive","METRIC")
            switch(HintPlacement.BOTH,hinted,false)
            switch(HintPlacement.ARTWORK_ONLY,"Evening drive",false)
            switch(HintPlacement.TITLE_ONLY,hinted,true)
            val original=AppState.presentation!!.artwork!!
            val expectedCover=Bitmap.createScaledBitmap(original,256,256,true)
            val actualCover=metadata()!!.getBitmap(MediaMetadata.METADATA_KEY_ART)!!
            check(actualCover.sameAs(expectedCover)){"Title-only MediaSession did not restore the original music cover"}
            if(expectedCover!==original)expectedCover.recycle()
            val titleOnlyDump=dump();val before=counters(titleOnlyDump)
            check("location_registered=false" in titleOnlyDump){"Title-only Google mode registered location"}
            check("navigation_artwork=false" in titleOnlyDump){"Title-only mode retained navigation artwork"}
            SystemClock.sleep(100)
            runOnMainSync{GuidanceHub.publish(key,guidance.copy(distanceMeters=110.0))}
            SystemClock.sleep(100)
            runOnMainSync{GuidanceHub.publish(key,guidance.copy(distanceMeters=100.0))}
            val latest=GuidanceFormatter.title(guidance.copy(distanceMeters=100.0),"Evening drive","METRIC")
            await("latest title-only distance",2300){AppState.metadataTitle==latest}
            val after=dump()
            check(counters(after)==before){"Title-only distance changes performed navigation artwork work"}
            check("pending_artwork=false" in after){"Title-only distance timer remained pending after publication"}
            check(AppState.artwork===original){"Title-only distance changes replaced the original music cover"}
            check(metadata()?.getString(MediaMetadata.METADATA_KEY_TITLE)==latest){"Latest title-only distance was absent from MediaSession"}
            switch(HintPlacement.BOTH,latest,false)
            runOnMainSync{GuidanceHub.clear()}
            await("music restored after guidance clear"){AppState.artwork===original && AppState.metadataTitle=="Evening drive"}
            placementReport="BOTH/ARTWORK_ONLY/TITLE_ONLY immediate=true; tagged cover+title MediaSession=true; title-only renderer counters unchanged=true; latest distance=true; guidance retained=true"
        }finally{
            if(ownsFixture){
                runOnMainSync{targetContext.stopService(Intent(targetContext,BluetoothAutoService::class.java));GuidanceHub.remove(key)}
                await("service cleanup",3000){!AppState.running}
                runOnMainSync{
                    activity?.finish()
                    val edit=prefs.edit();keys.forEach{edit.remove(it)}
                    baseline.forEach{(name,value)->when(value){
                        is String->edit.putString(name,value);is Boolean->edit.putBoolean(name,value)
                        is Int->edit.putInt(name,value);is Long->edit.putLong(name,value);is Float->edit.putFloat(name,value)
                        is Set<*>->edit.putStringSet(name,value.filterIsInstance<String>().toSet())
                    }}
                    check(edit.commit()){"Placement fixture preference restoration failed"}
                    check(prefs.all.filterKeys{it in keys}==baseline){"Placement fixture did not restore exact preferences/queue"}
                }
            }
            check(!file.exists() || file.delete()){"Placement audio fixture cleanup failed"}
        }
    }
    /** Actual service/MediaSession publication with synthetic guidance and no GPS/map requests. */
    private fun nativeAdaptiveCadence(){
        data class Event(val title:String,val at:Long)
        val events=mutableListOf<Event>()
        val prefs=targetContext.getSharedPreferences("bluetoothauto",Context.MODE_PRIVATE)
        val preferenceKeys=setOf("navigator","map","audioMode","units","hintPlacement")
        val baseline=prefs.all.filterKeys{it in preferenceKeys}
        var ownsFixture=false
        var activity:android.app.Activity?=null
        var lastTitle=""
        val observer:()->Unit={
            val title=AppState.metadataTitle
            if(title!=lastTitle){synchronized(events){events.add(Event(title,SystemClock.elapsedRealtime()))};lastTitle=title}
        }
        val reports=mutableListOf<String>()
        val key="NATIVE_CADENCE_FIXTURE"
        fun title(guidance:Guidance)=GuidanceFormatter.title(guidance,"BluetoothAuto","METRIC")
        fun publish(instruction:String,distance:Double):String {
            val guidance=Guidance(instruction,distance,source="Cadence test")
            runOnMainSync{GuidanceHub.publish(key,guidance)}
            return title(guidance)
        }
        fun awaitEvent(expected:String,timeout:Long):Event {
            val until=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<until){
                synchronized(events){events.firstOrNull{it.title==expected}}?.let{return it}
                SystemClock.sleep(20)
            }
            error("Timed out waiting for actual service title: $expected; events="+synchronized(events){events.toList()})
        }
        fun stereoTitle(expected:String){
            val sessions=targetContext.getSystemService(MediaSessionManager::class.java)
            val component=ComponentName(targetContext,MapsNotificationListener::class.java)
            val until=SystemClock.elapsedRealtime()+1000
            var observed:String?=null
            while(SystemClock.elapsedRealtime()<until){
                observed=sessions.getActiveSessions(component).firstOrNull{it.packageName==targetContext.packageName}
                    ?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                if(observed==expected)return
                SystemClock.sleep(20)
            }
            error("Actual MediaSession title differs: expected=$expected observed=$observed")
        }
        fun phase(name:String,base:Double,latest:Double,interval:Long){
            val instruction="Turn right onto $name Test Road"
            val initial=awaitEvent(publish(instruction,base),1000)
            SystemClock.sleep(90)
            val superseded=publish(instruction,base-5)
            SystemClock.sleep(90)
            val target=publish(instruction,latest)
            val accepted=awaitEvent(target,interval+1500)
            val elapsed=accepted.at-initial.at
            check(elapsed>=interval*0.8 && elapsed<=interval+maxOf(450L,interval/5)){
                "$name cadence expected${interval}ms, observed${elapsed}ms"
            }
            check(synchronized(events){events.none{it.title==superseded}}){"Superseded distance was published for $name"}
            stereoTitle(target)
            reports.add("$name target=${interval}ms observed=${elapsed}ms latest=$latest coalesced=true MediaSession=true")
        }
        try{
            runOnMainSync{
                check(!AppState.running && GuidanceHub.current==null){"Stop the existing navigation/session before running cadence acceptance"}
                check(prefs.edit().putString("navigator","GOOGLE").putBoolean("map",false)
                    .putString("audioMode","OFF").putString("units","METRIC").putString("hintPlacement","BOTH").commit())
                ownsFixture=true
                AppState.observers.add(observer)
            }
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync{targetContext.startForegroundService(Intent(targetContext,BluetoothAutoService::class.java).setAction(BluetoothAutoService.START))}
            val startupUntil=SystemClock.elapsedRealtime()+3000
            var running=false
            while(SystemClock.elapsedRealtime()<startupUntil && !running){runOnMainSync{running=AppState.running};if(!running)SystemClock.sleep(20)}
            check(running){"Native foreground session did not start"}

            phase("Near",120.0,100.0,1000)
            var unchangedBitmap:Bitmap?=null
            var unchangedTitle=""
            var unchangedCount=0
            runOnMainSync{unchangedBitmap=AppState.artwork;unchangedTitle=AppState.metadataTitle;unchangedCount=synchronized(events){events.size}}
            publish("Turn right onto Near Test Road",100.0)
            SystemClock.sleep(1200)
            runOnMainSync{
                check(AppState.artwork===unchangedBitmap){"Unchanged guidance repainted artwork"}
                check(AppState.metadataTitle==unchangedTitle && synchronized(events){events.size}==unchangedCount){"Unchanged guidance republished title"}
            }
            reports.add("unchanged guidance: no repaint/publication")

            awaitEvent(publish("Turn right onto Clear Test Road",100.0),1000)
            SystemClock.sleep(90)
            val clearedPending=publish("Turn right onto Clear Test Road",90.0)
            val clearedAt=SystemClock.elapsedRealtime()
            runOnMainSync{GuidanceHub.clear()}
            val clearUntil=SystemClock.elapsedRealtime()+700
            var clear=false
            while(SystemClock.elapsedRealtime()<clearUntil && !clear){runOnMainSync{clear=AppState.metadataTitle=="BluetoothAuto"};if(!clear)SystemClock.sleep(20)}
            check(clear){"Guidance clear was delayed"}
            SystemClock.sleep(1100)
            check(synchronized(events){events.none{it.title==clearedPending && it.at>=clearedAt}}){"Cleared pending distance was later published"}
            reports.add("clear immediate; pending canceled")

            phase("Approach",450.0,430.0,2000)
            phase("Far",900.0,880.0,5000)
            val oldPending=publish("Turn right onto Far Test Road",870.0)
            SystemClock.sleep(90)
            val newAt=SystemClock.elapsedRealtime()
            val newTitle=publish("Turn left onto New Turn Test Road",50.0)
            val newEvent=awaitEvent(newTitle,800)
            check(newEvent.at-newAt<=700){"New turn was not immediate"}
            stereoTitle(newTitle)
            reports.add("new turn immediate=${newEvent.at-newAt}ms")

            val stoppedPending=publish("Turn left onto New Turn Test Road",40.0)
            runOnMainSync{BluetoothAutoService.command(targetContext,BluetoothAutoService.STOP)}
            val stopUntil=SystemClock.elapsedRealtime()+1500
            var stopped=false
            while(SystemClock.elapsedRealtime()<stopUntil && !stopped){runOnMainSync{stopped=!AppState.running};if(!stopped)SystemClock.sleep(20)}
            check(stopped){"Native service did not stop"}
            val stoppedAt=SystemClock.elapsedRealtime()
            SystemClock.sleep(5200)
            runOnMainSync{check(AppState.artwork==null && AppState.metadataTitle=="BluetoothAuto"){"Artwork/title appeared after STOP"}}
            check(synchronized(events){events.none{it.at>=stoppedAt && it.title in setOf(stoppedPending,oldPending)}}){"Pending publication survived STOP"}
            reports.add("STOP: no pending title/image after destroy")
            cadenceReport=reports.joinToString("\n")
            File(targetContext.cacheDir,"adaptive-cadence-native.txt").writeText(cadenceReport+"\n")
        }finally{
          if(ownsFixture){
            runOnMainSync{
                targetContext.stopService(Intent(targetContext,BluetoothAutoService::class.java))
                GuidanceHub.clear();AppState.observers.remove(observer)
            }
            val cleanupUntil=SystemClock.elapsedRealtime()+2500
            var stopped=false
            while(SystemClock.elapsedRealtime()<cleanupUntil && !stopped){runOnMainSync{stopped=!AppState.running};if(!stopped)SystemClock.sleep(20)}
            runOnMainSync{
                activity?.finish()
                val edit=prefs.edit();preferenceKeys.forEach{edit.remove(it)}
                baseline.forEach{(key,value)->when(value){is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value)}}
                check(edit.commit()){"Cadence fixture preference restoration failed"}
            }
          }
        }
    }

    /** Distance-only composition reuses a real native map with synthetic public-location geometry. */
    private fun nativeCachedDistanceArtwork(){
        val junction=GeoPoint(47.6062,-122.3321)
        val west=GeoPoint(47.6062,-122.3328)
        val north=GeoPoint(47.6068,-122.3321)
        val geometry=TurnGeometry(listOf(west,junction,north),junction,40.0,"synthetic-cache-test","cached-distance-fixture")
        val initial=CountDownLatch(1)
        var renderer:MapArtworkRenderer?=null
        var latestBitmap:Bitmap?=null
        var stableMap:Bitmap?=null
        var closedCallbacks=0
        var moving=false
        fun render(distance:Double){
            val point=if(moving)Position(north.latitude+0.003,north.longitude+0.003,280f,1f,receivedAt=SystemClock.elapsedRealtime(),speedMps=28f)
                else Position(west.latitude,west.longitude,90f,1f,receivedAt=SystemClock.elapsedRealtime(),speedMps=8f)
            renderer!!.render(point,
                Guidance("Turn left onto Cache Test Road",distance,source="Cache test"),"METRIC",geometry){bitmap,status->
                latestBitmap=bitmap;closedCallbacks++
                if(status.contains("Upcoming turn"))initial.countDown()
            }
        }
        try{
            runOnMainSync{renderer=MapArtworkRenderer(targetContext);render(130.0)}
            check(initial.await(20,TimeUnit.SECONDS)){"Initial cached-map fixture snapshot unavailable"}
            var initialCompositions=0L
            runOnMainSync{initialCompositions=renderer!!.diagnostics().compositionCount;stableMap=latestBitmap!!.copy(Bitmap.Config.ARGB_8888,false)}
            repeat(10){index->SystemClock.sleep(1000);runOnMainSync{render(129.0-index)}}
            // Cross both old 15s refresh and 45s eviction thresholds with a moving phone.
            SystemClock.sleep(36_000)
            runOnMainSync{moving=true;render(80.0)}
            runOnMainSync{
                val stats=renderer!!.diagnostics()
                check(stats.snapshotRequests==1L){"Distance changes requested ${stats.snapshotRequests} native snapshots"}
                check(stats.cacheHits>=8L){"Expected cached-map hits, got ${stats.cacheHits}"}
                check(stats.compositionCount-initialCompositions>=10L){"Distance-only composition updates were missing"}
                check(stats.staticLayerBuilds==1L){"Distance changes rebuilt ${stats.staticLayerBuilds} static map layers"}
                val first=stableMap!!;val latest=latestBitmap!!
                for(y in 0 until 320)for(x in 0 until 512)check(first.getPixel(x,y)==latest.getPixel(x,y)){"The fixed turn map moved or changed"}
                check(!first.sameAs(latest)){"The distance footer did not update"}
                cacheReport="snapshots=${stats.snapshotRequests} compositions=${stats.compositionCount} cacheHits=${stats.cacheHits} staticLayers=${stats.staticLayerBuilds} updates=11 elapsed>46s; moving phone map pixels identical=true; distance footer changed=true"
                File(targetContext.cacheDir,"cached-distance-native.txt").writeText(cacheReport+"\n")
                renderer!!.close()
            }
            val callbacksAtClose=closedCallbacks;val imageAtClose=latestBitmap
            SystemClock.sleep(1200)
            runOnMainSync{check(closedCallbacks==callbacksAtClose && latestBitmap===imageAtClose){"Closed renderer emitted a later image"}}
        }finally{runOnMainSync{renderer?.close();stableMap?.recycle()}}
    }

    private fun nativeRoute(){
        val old=Library.navigator(targetContext);val done=CountDownLatch(1)
        val prefs=targetContext.getSharedPreferences("own-map-destinations",Context.MODE_PRIVATE);val recent=prefs.getString("recent",null)
        var failure:Throwable?=null
        var requestedAt=0L
        var routeSucceeded=false
        val timing=org.json.JSONObject().put("sample_count",1).put("online_route_request",true)
            .put("gps_injected",false).put("whole_trip_tested",false)
        ownRouteTiming=timing
        val observer:(OwnNavigation.Snapshot)->Unit={s->if(!s.routing && (s.route!=null || s.error!=null)){
            if(s.route==null)failure=IllegalStateException("Public route unavailable")
            else if(!timing.has("online_route_fetch_completion_ms")){
                timing.put("online_route_fetch_completion_ms",(SystemClock.elapsedRealtime()-requestedAt).coerceAtLeast(0))
                    .put("route_point_count",s.route.points.size)
            }
            done.countDown()
        }}
        try{
            runOnMainSync{
                Library.navigator(targetContext,"OPEN");OwnNavigation.initialize(targetContext);OwnNavigation.stop()
                OwnNavigation.observers.add(observer)
                OwnNavigation.updatePosition(targetContext,Position(47.6062,-122.3321,0f,5f,receivedAt=SystemClock.elapsedRealtime()))
                requestedAt=SystemClock.elapsedRealtime()
                OwnNavigation.start(targetContext,OwnMapProvider.Place("Public church test",GeoPoint(47.396414,-122.301425)))
            }
            check(done.await(45,TimeUnit.SECONDS)){"Public route timeout"};failure?.let{throw it}
            runOnMainSync{check(OwnNavigation.snapshot.route!!.points.size>20);check(OwnNavigation.guidance!=null);check(OwnNavigation.active)}
            if(googleGeometryTest)nativeGoogleGeometry()
            nativeTurnArtwork()
            routeSucceeded=true
        }finally{
            runOnMainSync{OwnNavigation.observers.remove(observer);OwnNavigation.stop();check(!OwnNavigation.active);targetContext.getSharedPreferences("bluetoothauto",Context.MODE_PRIVATE).edit().putString("navigator",old).commit();if(recent==null)prefs.edit().remove("recent").commit() else prefs.edit().putString("recent",recent).commit()}
            timing.put("result",if(routeSucceeded)"PASS"else"FAIL")
            File(targetContext.cacheDir,"own-route-timing.json").writeText(timing.toString(2)+"\n")
            ownRouteTiming=null
        }
    }
    /** Native projection acceptance, not a claim that synthetic L paths match Seattle roads. */
    private fun nativeApproachUp(){
        val junction=GeoPoint(47.6062,-122.3321)
        fun offset(bearing:Double,meters:Double):GeoPoint {
            val angle=meters/6_371_008.8
            val course=Math.toRadians((bearing+360)%360)
            val latitude=Math.toRadians(junction.latitude)
            val longitude=Math.toRadians(junction.longitude)
            val resultLatitude=kotlin.math.asin(kotlin.math.sin(latitude)*kotlin.math.cos(angle)+
                kotlin.math.cos(latitude)*kotlin.math.sin(angle)*kotlin.math.cos(course))
            val resultLongitude=longitude+kotlin.math.atan2(kotlin.math.sin(course)*kotlin.math.sin(angle)*kotlin.math.cos(latitude),
                kotlin.math.cos(angle)-kotlin.math.sin(latitude)*kotlin.math.sin(resultLatitude))
            return GeoPoint(Math.toDegrees(resultLatitude),Math.toDegrees(resultLongitude))
        }
        val reports=mutableListOf<String>()
        for(heading in listOf(0,45,90,135,180,225,270,315)){
            val approach=offset(heading+180.0,35.0)
            val farApproach=offset(heading+180.0,70.0)
            val right=offset(heading+90.0,35.0)
            val left=offset(heading-90.0,35.0)
            val farRight=offset(heading+90.0,70.0)
            val geometry=TurnGeometry(listOf(farApproach,approach,junction,right,farRight),junction,40.0,"synthetic-projection-test","projection:$heading")
            // GPS course deliberately differs: framing should use the supplied approach road.
            val position=Position(farApproach.latitude,farApproach.longitude,((heading+25)%360).toFloat(),1f,
                receivedAt=SystemClock.elapsedRealtime(),speedMps=8f)
            val done=CountDownLatch(1)
            var snapshotter:MapSnapshotter?=null
            var bitmap:Bitmap?=null
            var failure:Throwable?=null
            try{
              runOnMainSync{
                MapLibre.getInstance(targetContext)
                val frame=TurnArtworkPolicy.frame(position,geometry)
                val options=MapSnapshotter.Options(512,512).withPixelRatio(1f).withStyle(MapArtworkRenderer.STYLE)
                    .withLogo(false).withAttribution(true)
                    .withCameraPosition(CameraPosition.Builder().target(LatLng(frame.center.latitude,frame.center.longitude))
                        .zoom(frame.zoom).bearing(frame.bearingDegrees).tilt(0.0).build())
                snapshotter=MapSnapshotter(targetContext,options)
                snapshotter!!.start({snapshot->
                    bitmap=snapshot.bitmap
                    try {
                        fun project(point:GeoPoint)=snapshot.pixelForLatLng(LatLng(point.latitude,point.longitude))
                        val j=project(junction);val a=project(approach);val r=project(right);val l=project(left)
                        val ax=a.x-j.x;val ay=a.y-j.y
                        val rx=r.x-j.x;val ry=r.y-j.y
                        val lx=l.x-j.x;val ly=l.y-j.y
                        check(listOf(ax,ay,rx,ry,lx,ly).all{it.isFinite()}){"Nonfinite native projection at $heading"}
                        check(ay>5f){"Approach did not come from below at $heading: ($ax,$ay), camera=${frame.bearingDegrees}"}
                        check(kotlin.math.abs(ax)<=maxOf(2f,ay*.08f)){"Approach tilted sideways at $heading: ($ax,$ay)"}
                        check(rx>5f && kotlin.math.abs(ry)<=maxOf(2f,rx*.08f)){"Right turn mirrored/tilted at $heading: ($rx,$ry)"}
                        check(lx< -5f && kotlin.math.abs(ly)<=maxOf(2f,-lx*.08f)){"Left turn mirrored/tilted at $heading: ($lx,$ly)"}
                        check((-ax)*ry-(-ay)*rx>0){"Native turn orientation was mirrored at $heading"}
                        reports.add("heading=$heading camera=${frame.bearingDegrees} approach=($ax,$ay) right=($rx,$ry) left=($lx,$ly)")
                    }catch(error:Throwable){failure=error}
                    finally{done.countDown()}
                },{message->failure=IllegalStateException("Native map projection snapshot failed at $heading: $message");done.countDown()})
              }
                check(done.await(20,TimeUnit.SECONDS)){"Native map projection snapshot timed out at $heading"}
                failure?.let{throw it}
            }finally{
                runOnMainSync{snapshotter?.cancel();bitmap?.takeUnless{it.isRecycled}?.recycle()}
            }
        }
        approachUpReport=reports.joinToString("\n")
        File(targetContext.cacheDir,"approach-up-native-projection.txt").writeText(
            "Synthetic geometry at public Seattle point; native production-frame MapLibre projection, not GPS or road matching.\n"+approachUpReport+"\n")
    }

    private fun nativeTurnArtwork(){
        val rendered=CountDownLatch(1)
        var renderer:MapArtworkRenderer?=null
        var result:Bitmap?=null
        var geometry:org.bluetoothauto.model.TurnGeometry?=null
        var renderRequestedAt=0L
        runOnMainSync{
            val r=OwnNavigation.snapshot.route!!
            // An exact public route point, not the phone's real GPS position.
            val point=r.points[1]
            OwnNavigation.updatePosition(targetContext,Position(point.latitude,point.longitude,null,1f,receivedAt=SystemClock.elapsedRealtime(),speedMps=8f))
            val geometryStarted=SystemClock.elapsedRealtimeNanos()
            geometry=OwnTurnGeometry.from(OwnNavigation.snapshot)
            ownRouteTiming?.put("local_turn_geometry_ms",(SystemClock.elapsedRealtimeNanos()-geometryStarted)/1_000_000.0)
            check(geometry!=null){"Public route point did not produce a confirmed turn"}
            renderer=MapArtworkRenderer(targetContext)
            renderRequestedAt=SystemClock.elapsedRealtime()
            renderer!!.render(OwnNavigation.snapshot.position,OwnNavigation.guidance,"IMPERIAL",geometry){bitmap,status->
                if(status.contains("Upcoming turn")){
                    ownRouteTiming?.put("native_artwork_ready_ms",(SystemClock.elapsedRealtime()-renderRequestedAt).coerceAtLeast(0))
                    ownRouteTiming?.put("native_snapshot_ms",renderer!!.diagnostics().lastSnapshotMs)
                    result=bitmap;rendered.countDown()
                }
            }
        }
        try{
            check(rendered.await(25,TimeUnit.SECONDS)){"Turn map snapshot unavailable"}
            val bitmap=result!!
            var blue=0
            for(y in 130 until 340)for(x in 0 until bitmap.width){val c=bitmap.getPixel(x,y);if(android.graphics.Color.blue(c)>180 && android.graphics.Color.red(c)<70 && android.graphics.Color.green(c) in 70..170)blue++}
            check(blue>100){"Confirmed route blue line missing"}
            File(targetContext.cacheDir,"public-turn-artwork.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            runOnMainSync{
                AppState.demo=true;AppState.guidance=OwnNavigation.guidance?.copy(source="Own maps · public preview",demo=true)
                AppState.artwork=bitmap;AppState.playback=PlaybackState(Track("file:///public-preview","Evening drive · preview","BluetoothAuto"),false,0,24_000)
                AppState.changed()
            }
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync();SystemClock.sleep(500)
            check(activity.hasWindowFocus()){ "Unlock the test phone before capturing its player UI" }
            val screenshot=uiAutomation.takeScreenshot()
            File(targetContext.cacheDir,"public-turn-player.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            screenshot.recycle()
            runOnMainSync{activity.finish();AppState.demo=false;AppState.artwork=null;AppState.playback=PlaybackState()}
        }finally{runOnMainSync{renderer?.close()}}
    }

    private fun nativeGoogleGeometry(){
        val done=CountDownLatch(1);var resolver:TurnGeometryResolver?=null
        runOnMainSync{
            val r=OwnNavigation.snapshot.route!!;val point=r.points[1]
            val next=r.points.drop(2).firstOrNull{it.distanceTo(point)>5} ?: r.points[2]
            val a=Math.toRadians(point.latitude);val b=Math.toRadians(next.latitude);val d=Math.toRadians(next.longitude-point.longitude)
            val course=((Math.toDegrees(kotlin.math.atan2(kotlin.math.sin(d)*kotlin.math.cos(b),kotlin.math.cos(a)*kotlin.math.sin(b)-kotlin.math.sin(a)*kotlin.math.cos(b)*kotlin.math.cos(d)))+360)%360).toFloat()
            OwnNavigation.updatePosition(targetContext,Position(point.latitude,point.longitude,course,1f,receivedAt=SystemClock.elapsedRealtime(),speedMps=8f))
            val g=OwnNavigation.guidance
            if(g==null){googleMatch="not-supported";done.countDown()}
            else{resolver=TurnGeometryResolver();resolver!!.resolve(OwnNavigation.snapshot.position!!,g.copy(source="Google Maps")){value->googleMatch=if(value==null)"not-confirmed" else "matched-public-OSM-junction";done.countDown()}}
        }
        try{check(done.await(18,TimeUnit.SECONDS)){"Bounded geometry lookup did not finish"}}
        finally{runOnMainSync{resolver?.close()}}
    }

}
