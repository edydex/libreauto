// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import org.bluetoothauto.artwork.MapArtworkRenderer
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GeoPoint
import org.bluetoothauto.navigation.TurnGeometryResolver
import org.bluetoothauto.navigation.TurnMatcher
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses opposite connected branches only as an approved-graph cache fixture, not a predicted trip. */
object PreparedTurnArtworkAcceptance {
    fun run(test: Instrumentation): String {
        val context=test.targetContext
        fun read(name:String,limit:Int):String {
            val file=File(context.cacheDir,name)
            check(file.isFile && file.length() in 1..limit.toLong()) { "Prepared-turn diagnostic fixture missing or too large" }
            return file.inputStream().use { input ->
                val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;check(bytes.size()+count<=limit);bytes.write(buffer,0,count)}
                bytes.toString(Charsets.UTF_8.name())
            }
        }
        val graph=TurnGeometryResolver.parseGraph(read("diagnostic-turn-graph.json",3_000_000))
        val input=JSONObject(read("diagnostic-turn-input.json",8192))
        val leftInstruction=input.getString("instruction")
        check(Regex("(?i)^turn\\s+left\\s+onto\\s+").containsMatchIn(leftInstruction)) { "Prepared-turn branch fixture needs a supported left turn" }
        val rightInstruction=leftInstruction.replaceFirst(Regex("(?i)^turn\\s+left"),"Turn right")
        fun match(instruction:String)=TurnMatcher.match(graph,TurnMatcher.Request(
            GeoPoint(input.getDouble("latitude"),input.getDouble("longitude")),input.getDouble("bearing"),
            input.getDouble("accuracy"),instruction,input.getDouble("distanceMeters")))
            ?: error("Prepared-turn diagnostic branch is not uniquely confirmed")
        fun geometry(m:TurnMatcher.Match)=TurnGeometry(m.points,m.junction,m.approachSpeedLimitKph,"matched",m.key)
        val left=geometry(match(leftInstruction));val right=geometry(match(rightInstruction))
        check(left.key!=right.key) { "Opposite branch fixture did not produce distinct geometry" }
        val secondFuture=right.copy(key="cache-fixture-second:${right.key}")
        val position=Position(input.getDouble("latitude"),input.getDouble("longitude"),input.getDouble("bearing").toFloat(),
            input.getDouble("accuracy").toFloat(),receivedAt=SystemClock.elapsedRealtime(),speedMps=0f)
        val leftGuidance=Guidance(leftInstruction,input.getDouble("distanceMeters"),source="Prepared branch fixture")
        val rightGuidance=leftGuidance.copy(instruction=rightInstruction)
        val initialReady=CountDownLatch(1);val firstPrepared=CountDownLatch(1);val secondPrepared=CountDownLatch(1)
        var renderer:MapArtworkRenderer?=null;var visible:Bitmap?=null;var initial:Bitmap?=null;var promoted:Bitmap?=null
        var visibleCallbacks=0;var preparedOne=false;var preparedTwo=false
        var snapshotsBefore=0L;var snapshotsAfter=0L;var preparedHits=0L;var cachedCount=0
        var cancelled=false;var cancelledResult=true
        try {
            test.runOnMainSync {
                renderer=MapArtworkRenderer(context)
                renderer!!.render(position,leftGuidance,"IMPERIAL",left){bitmap,status->
                    visible=bitmap;visibleCallbacks++
                    if(status=="Upcoming turn")initialReady.countDown()
                }
            }
            check(initialReady.await(25,TimeUnit.SECONDS)) { "Current native map unavailable for prepared-turn test" }
            var callbacksBefore=0
            test.runOnMainSync {
                initial=visible!!.copy(Bitmap.Config.ARGB_8888,false);callbacksBefore=visibleCallbacks
                snapshotsBefore=renderer!!.diagnostics().snapshotRequests
                val fresh=position.copy(receivedAt=SystemClock.elapsedRealtime())
                renderer!!.prepare(fresh,rightGuidance,"IMPERIAL",right){preparedOne=it;firstPrepared.countDown()}
                renderer!!.prepare(fresh,rightGuidance,"IMPERIAL",secondFuture){preparedTwo=it;secondPrepared.countDown()}
            }
            check(firstPrepared.await(25,TimeUnit.SECONDS) && secondPrepared.await(25,TimeUnit.SECONDS) && preparedOne && preparedTwo) {
                "Serial future-map preparation failed"
            }
            test.runOnMainSync {
                val stats=renderer!!.diagnostics()
                check(visibleCallbacks==callbacksBefore && initial!!.sameAs(visible!!)) { "Prefetch changed the visible current artwork" }
                check(stats.snapshotRequests==snapshotsBefore+2 && stats.prefetchSnapshotRequests==2L && stats.preparedMapCount==2 && !stats.isPreparing) {
                    "Two serial future maps were not retained in the bounded cache"
                }
                snapshotsBefore=stats.snapshotRequests
                renderer!!.resetTurn(keepPrepared=true)
                renderer!!.render(position.copy(receivedAt=SystemClock.elapsedRealtime(),bearing=250f,speedMps=20f),
                    rightGuidance,"IMPERIAL",right){bitmap,status->
                    visibleCallbacks++;check(status=="Upcoming turn") { "Prepared promotion did not immediately return its native map" }
                    promoted=bitmap.copy(Bitmap.Config.ARGB_8888,false)
                }
                val after=renderer!!.diagnostics();snapshotsAfter=after.snapshotRequests;preparedHits=after.preparedCacheHits;cachedCount=after.preparedMapCount
                check(promoted!=null && snapshotsAfter==snapshotsBefore && preparedHits>=1 && cachedCount==1) {
                    "Future turn promotion requested a new snapshot or lost the second queued map"
                }
            }
            val before=IntArray(512*320);val after=IntArray(512*320)
            initial!!.getPixels(before,0,512,0,0,512,320);promoted!!.getPixels(after,0,512,0,0,512,320)
            check(!before.contentEquals(after)) { "Opposite connected branches produced identical upper map artwork" }
            File(context.cacheDir,"prepared-turn-current.png").outputStream().use { check(initial!!.compress(Bitmap.CompressFormat.PNG,100,it)) }
            File(context.cacheDir,"prepared-turn-promoted.png").outputStream().use { check(promoted!!.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val cancellation=CountDownLatch(1)
            test.runOnMainSync {
                renderer!!.resetTurn()
                check(renderer!!.diagnostics().preparedMapCount==0) { "Reroute reset retained stale future artwork" }
                renderer!!.prepare(position.copy(receivedAt=SystemClock.elapsedRealtime()),rightGuidance,"IMPERIAL",
                    right.copy(key="cancelled-cache-fixture:${right.key}")){cancelledResult=it;cancellation.countDown()}
                check(renderer!!.diagnostics().isPreparing) { "Cancellation fixture did not start future work" }
                renderer!!.resetTurn()
                check(!renderer!!.diagnostics().isPreparing && renderer!!.diagnostics().preparedMapCount==0)
                renderer!!.close()
            }
            check(cancellation.await(3,TimeUnit.SECONDS) && !cancelledResult) { "Cancelled future request reported success or never completed" }
            cancelled=true
            val callbacksAtClose=visibleCallbacks;SystemClock.sleep(150)
            test.runOnMainSync { check(visibleCallbacks==callbacksAtClose) { "Closed prepared renderer published stale artwork" } }
            val receipt=JSONObject().put("graph_source","operator_supplied_one_time_approved_diagnostic")
                .put("fixture","opposite_connected_branches_for_cache_acceptance_only").put("user_next_turn_established",false)
                .put("production_overpass_success_established",false).put("two_future_maps_prepared_serially",true)
                .put("current_artwork_unchanged_during_prepare",true).put("promotion_immediate",true)
                .put("snapshots_before_promotion",snapshotsBefore).put("snapshots_after_promotion",snapshotsAfter)
                .put("prepared_cache_hits",preparedHits).put("prepared_maps_remaining_after_promotion",cachedCount)
                .put("opposite_branch_upper_pixels_differ",true).put("reroute_cleared_prepared_cache",true)
                .put("pending_preparation_cancelled",cancelled).put("renderer_closed",true)
                .put("app_state_changed",false).put("preferences_changed",false).put("gps_injected",false)
            File(context.cacheDir,"prepared-turn-artwork-result.json").writeText(receipt.toString(2)+"\n")
            return "approved-graph opposite-branch cache fixture; two serial prepared maps; current output unchanged; instant promotion without snapshot; reroute clear/cancel; no trip prediction or GPS/app-state changes"
        } finally {
            test.runOnMainSync {renderer?.close()};initial?.recycle();promoted?.recycle()
        }
    }
}
