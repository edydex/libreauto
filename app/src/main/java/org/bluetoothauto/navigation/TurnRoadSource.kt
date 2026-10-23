// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.HttpURLConnection
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Worker-only, bounded road details for a captured trip; shares the ordinary prepared-road store. */
internal class TurnRoadSource(context: Context):Closeable {
    private val store=TurnRoadStore(context.applicationContext)
    @Volatile private var connection:HttpURLConnection?=null
    @Volatile private var closed=false
    private val cancelled=AtomicLong()
    @Volatile var networkAttempts=0;private set
    @Volatile var savedHits=0;private set

    fun load(center:GeoPoint,radius:Double):TurnRoadStore.Area {
        check(!closed)
        require(radius.isFinite() && radius in 100.0..1500.0)
        val ticket=cancelled.get()
        fun stopped()=closed || cancelled.get()!=ticket || Thread.currentThread().isInterrupted
        val fetchRadius=(radius+100).coerceAtMost(1500.0)
        val url=TurnGeometryTransport.getUrl(TurnGeometryTransport.areaQuery(center,fetchRadius))
        store.load(center,radius)?.let{if(stopped())throw InterruptedException();savedHits++;return it}
        awaitDownloadSlot{stopped()}
        if(stopped())throw InterruptedException()
        val active=url.toURL().openConnection() as HttpURLConnection
        connection=active;networkAttempts++
        val expired=AtomicBoolean(false)
        val deadline=Timer("trip-road-deadline",true)
        deadline.schedule(object:TimerTask(){override fun run(){expired.set(true);try{active.disconnect()}catch(_:RuntimeException){}}},12_000)
        try {
            active.requestMethod="GET";active.connectTimeout=4000;active.readTimeout=8000;active.instanceFollowRedirects=false
            active.setRequestProperty("User-Agent","BluetoothAuto/0.2 (bounded trip turn preparation)")
            active.setRequestProperty("Accept","application/json")
            val status=active.responseCode
            if(status!=200)throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.IO,status)
            val data=active.inputStream.use{input->ByteArrayOutputStream().use{out->
                val buffer=ByteArray(8192)
                while(true){
                    if(stopped())throw InterruptedException()
                    val n=input.read(buffer);if(n<0)break
                    if(out.size()+n>3_000_000)throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.TOO_LARGE)
                    out.write(buffer,0,n)
                }
                out.toString("UTF-8")
            }}
            if(stopped())throw InterruptedException()
            return store.save(center,fetchRadius,data)
        }catch(error:Exception){
            if(stopped())throw InterruptedException()
            if(expired.get())throw TurnGeometryTransport.Failure(TurnGeometryTransport.State.TIMEOUT)
            throw error
        }finally{deadline.cancel();active.disconnect();if(connection===active)connection=null}
    }
    fun cancel(){
        cancelled.incrementAndGet()
        val active=connection
        if(active!=null)Thread({try{active.disconnect()}catch(_:RuntimeException){}},"trip-road-cancel").apply{isDaemon=true;start()}
    }
    override fun close(){closed=true;cancel()}
    companion object {
        private val gate=Any()
        private var lastDownload=0L
        /** Both current-turn and trip workers use one modest public-service ceiling. */
        fun awaitDownloadSlot(cancelled:()->Boolean={false}){
            while(true){
                if(Thread.currentThread().isInterrupted || cancelled())throw InterruptedException()
                val wait=synchronized(gate){
                    if(Thread.currentThread().isInterrupted || cancelled())throw InterruptedException()
                    val now=System.nanoTime()
                    val remaining=if(lastDownload==0L)0L else 30_000_000_000L-(now-lastDownload)
                    if(remaining<=0){lastDownload=now;0L}else remaining
                }
                if(wait<=0)return
                Thread.sleep(((wait+999_999)/1_000_000).coerceAtMost(500))
            }
        }
    }
}
