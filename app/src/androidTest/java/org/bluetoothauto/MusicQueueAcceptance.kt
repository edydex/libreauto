// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import org.bluetoothauto.model.PlaybackState
import org.bluetoothauto.model.Track
import org.bluetoothauto.playback.VlcPlayer
import org.bluetoothauto.playback.PlaylistParser
import java.io.File

/** Real native playback with disposable original audio. Never reads or changes the saved queue. */
object MusicQueueAcceptance {
    fun run(test: Instrumentation, target: Context, assets: Context): String {
        val folder=File(target.cacheDir,"music-queue-${SystemClock.elapsedRealtimeNanos()}")
        check(folder.mkdir())
        var player:VlcPlayer?=null
        var state=PlaybackState()
        var activity:android.app.Activity?=null
        fun await(label:String, condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+8000
            while(SystemClock.elapsedRealtime()<until){
                var ready=false;test.runOnMainSync{ready=condition()};if(ready)return
                SystemClock.sleep(20)
            }
            error("Native music acceptance timed out: $label")
        }
        try {
            val m3u=PlaylistParser.parse("#EXTM3U\n#EXTINF:24,First song\nTrack 2.m4a\nTrack 10.m4a\nTrack 2.m4a\nhttps://invalid.example/stream\n".toByteArray(),"local.m3u8")
            check(m3u.entries.map{it.location}==listOf("Track 2.m4a","Track 10.m4a","Track 2.m4a") && m3u.skipped==1)
            val xspf=PlaylistParser.parse("""<?xml version="1.0"?><playlist version="1" xmlns="http://xspf.org/ns/0/"><trackList><track><location>Track%202.m4a</location><title>Test song</title></track></trackList></playlist>""".toByteArray(),"local.xspf")
            check(xspf.entries.single().location=="Track%202.m4a" && xspf.entries.single().title=="Test song"){"Android SAX XSPF import failed"}
            val unsafe=PlaylistParser.parse("""<!DOCTYPE playlist [<!ENTITY bad SYSTEM "file:///data/local/tmp/not-read">]><playlist><trackList><track><location>&bad;</location></track></trackList></playlist>""".toByteArray(),"unsafe.xspf")
            check(unsafe.entries.isEmpty())
            val tracks=(1..4).map{index->
                val file=File(folder,"Track $index.m4a")
                assets.assets.open("tagged-test.m4a").use{input->file.outputStream().use{input.copyTo(it)}}
                Track(Uri.fromFile(file).toString(),"Track $index")
            }
            activity=test.startActivitySync(Intent(target,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.runOnMainSync{player=VlcPlayer(target){state=it};player!!.setVolume(0);player!!.setQueue(tracks,0,true)}
            await("ordered first track"){state.playing && state.track?.uri==tracks[0].uri && state.durationMs>20_000}
            test.runOnMainSync{player!!.next()}
            await("ordered next track"){state.playing && state.track?.uri==tracks[1].uri}
            test.runOnMainSync{player!!.seekTo(5000)}
            await("seek"){state.positionMs>=4900}
            val before=state.positionMs
            test.runOnMainSync{player!!.setShuffle(true)}
            test.runOnMainSync{
                check(state.track?.uri==tracks[1].uri && state.positionMs>=before-50 && state.playing){"Shuffle restarted the playing track"}
            }
            val order=mutableListOf(state.track!!.uri)
            repeat(3){
                val old=state.track!!.uri
                test.runOnMainSync{player!!.next()}
                await("unique shuffled track"){state.playing && state.track?.uri!=old}
                order.add(state.track!!.uri)
            }
            check(order.toSet()==tracks.map{it.uri}.toSet()){"Shuffle repeated or omitted a track"}
            val last=order.last()
            test.runOnMainSync{player!!.previous()}
            await("shuffle previous history"){state.playing && state.track?.uri==order[order.lastIndex-1]}
            test.runOnMainSync{player!!.next()}
            await("shuffle next after previous"){state.playing && state.track?.uri==last}
            val current=state.track!!.uri
            test.runOnMainSync{player!!.setShuffle(false)}
            check(state.track?.uri==current){"Returning to ordered playback changed the current file"}
            test.runOnMainSync{player!!.seekTo(6000)}
            await("position before queue append"){state.playing && state.positionMs>=5900}
            val position=state.positionMs
            test.runOnMainSync{player!!.updateQueue(tracks+Track(tracks[0].uri,"Repeated playlist entry"))}
            test.runOnMainSync{
                check(state.track?.uri==current && state.positionMs>=position-50 && state.playing){"Appending imports restarted playback"}
                check(state.queueSize==5){"Updated queue size was not published"}
            }
            return "native M3U8/XSPF SAX/local-only/duplicate parsing; LibVLC ordered next; shuffle preserves track/position, visits all four once, previous history; mode switch and queue append preserve playback"
        } finally {
            test.runOnMainSync{player?.close();activity?.finish()}
            folder.listFiles()?.forEach{check(it.delete())};check(folder.delete())
        }
    }
}
