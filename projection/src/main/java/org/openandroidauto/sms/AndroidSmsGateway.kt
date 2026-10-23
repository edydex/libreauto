// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.UUID

/** Only a consumed explicit Send ticket reaches this carrier transport. No retries, queues or persistence. */
class AndroidSmsGateway(context: Context) : Closeable {
    enum class Result { SENT_TO_CARRIER, FAILED, TIMED_OUT, UNAVAILABLE }
    private val context=context.applicationContext
    private val main=Handler(Looper.getMainLooper())
    private var pending: Attempt?=null
    private var closed=false
    private class Attempt(val callback:(Result)->Unit) {
        lateinit var receiver:BroadcastReceiver
        lateinit var timeout:Runnable
        val intents=ArrayList<PendingIntent>()
        var remaining=0
    }
    fun send(dispatch: SmsSendGate.Dispatch, callback:(Result)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(Build.VERSION.SDK_INT<32){callback(Result.UNAVAILABLE);return}
        if(closed||pending!=null||!SmsAccess.canSend(context)||SmsAccess.defaultSubscription()!=dispatch.subscriptionId){callback(Result.UNAVAILABLE);return}
        val manager=try{context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(dispatch.subscriptionId)}catch(_:RuntimeException){null}
        if(manager==null){callback(Result.UNAVAILABLE);return}
        val parts=try{manager.divideMessage(dispatch.body)}catch(_:RuntimeException){callback(Result.UNAVAILABLE);return}
        if(parts.isEmpty()||parts.size>10){callback(Result.UNAVAILABLE);return}
        val attempt=Attempt(callback);pending=attempt
        val action="${context.packageName}.SMS_RESULT.${UUID.randomUUID()}"
        val partCount=parts.size
        attempt.remaining=partCount
        val received=HashSet<Int>()
        attempt.receiver=object:BroadcastReceiver(){override fun onReceive(ignored:Context?,intent:Intent?){
            if(pending!==attempt||intent?.action!=action)return
            val part=intent.getIntExtra("part",-1)
            if(part !in 0 until partCount||!received.add(part))return
            if(resultCode!=Activity.RESULT_OK){finish(attempt,Result.FAILED);return}
            attempt.remaining--;if(attempt.remaining==0)finish(attempt,Result.SENT_TO_CARRIER)
        }}
        attempt.timeout=Runnable{finish(attempt,Result.TIMED_OUT)}
        try {
            ContextCompat.registerReceiver(context,attempt.receiver,IntentFilter(action),ContextCompat.RECEIVER_NOT_EXPORTED)
            parts.indices.forEach { index ->
                val intent=Intent(action).setPackage(context.packageName).setData(Uri.parse("openauto-sms-result://part/$index")).putExtra("part",index)
                attempt.intents+=PendingIntent.getBroadcast(context,index,intent,PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            }
            main.postDelayed(attempt.timeout,60_000)
            // No default/slot fallback. Recheck immediately before the only external sending API.
            if(!SmsAccess.canSend(context)||SmsAccess.defaultSubscription()!=dispatch.subscriptionId){finish(attempt,Result.UNAVAILABLE);return}
            if(parts.size==1)manager.sendTextMessage(dispatch.number,null,parts[0],attempt.intents[0],null)
            else manager.sendMultipartTextMessage(dispatch.number,null,parts,attempt.intents,null)
        } catch(_:SecurityException){finish(attempt,Result.UNAVAILABLE)}
        catch(_:RuntimeException){finish(attempt,Result.FAILED)}
    }
    private fun finish(attempt:Attempt,result:Result){
        if(pending!==attempt)return
        pending=null;main.removeCallbacks(attempt.timeout)
        try{context.unregisterReceiver(attempt.receiver)}catch(_:RuntimeException){}
        attempt.intents.forEach{it.cancel()};attempt.intents.clear()
        if(!closed)attempt.callback(result)
    }
    override fun close(){closed=true;pending?.let{finish(it,Result.UNAVAILABLE)}}
}
