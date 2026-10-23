// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File

/** Observes an already-authorized live Google navigation test without changing GPS or preferences. */
object LiveGoogleArtworkAcceptance {
    fun run(test:Instrumentation):String {
        val context=test.targetContext
        test.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(label:String,timeout:Long,ready:()->Boolean){
            val deadline=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<deadline){
                var value=false;test.runOnMainSync{value=ready()};if(value)return
                SystemClock.sleep(100)
            }
            error("Live Google acceptance timed out: $label")
        }
        await("active navigation listener",15_000){AppState.listenerConnected && GuidanceHub.current!=null}
        test.runOnMainSync{
            check(!AppState.demo && Library.navigator(context)=="GOOGLE" && Library.mapEnabled(context) && Library.hintPlacement(context).inArtwork)
            BluetoothAutoService.command(context,BluetoothAutoService.START)
        }
        await("fresh travel course",20_000){AppState.position?.bearing!=null}
        fun capturePose() { test.runOnMainSync {
            val p=AppState.position;val g=AppState.guidance
            val value=org.json.JSONObject().put("latitude",p?.latitude).put("longitude",p?.longitude)
                .put("bearing",p?.bearing).put("accuracy",p?.accuracy).put("speedMps",p?.speedMps)
                .put("instruction",g?.instruction).put("distanceMeters",g?.distanceMeters)
            File(context.cacheDir,"live-google-pose.json").writeText(value.toString())
        } }
        capturePose()
        try {
            await("confirmed Google junction and map snapshot",80_000){
                AppState.turnGeometry!=null && AppState.artworkStatus.contains("Upcoming turn") && AppState.artwork!=null
            }
        } finally {
            capturePose()
        }
        test.runOnMainSync{
            File(context.cacheDir,"live-google-turn-artwork.png").outputStream().use{
                check(AppState.artwork!!.compress(Bitmap.CompressFormat.PNG,100,it))
            }
        }
        val read=android.os.ParcelFileDescriptor.AutoCloseInputStream(
            test.uiAutomation.executeShellCommand("dumpsys activity service org.bluetoothauto/.BluetoothAutoService")
        ).bufferedReader().use{it.readText().take(32768)}
        check(("turn_geometry=matched" in read || "turn_geometry=candidate-constrained" in read) &&
            "turn_lookup_gate=CONFIRMED" in read && "map_snapshots=0 " !in read){"Live turn or map counters missing"}
        check(Regex("map_native_route_snapshots=[1-9][0-9]*").containsMatchIn(read)) { "Live route did not use native map line layers" }
        File(context.cacheDir,"live-google-turn-diagnostic.txt").writeText(read)
        return "active Google notification; fresh course; validated OSM/routed junction; native MapLibre snapshot; GPS/preferences preserved"
    }
}
