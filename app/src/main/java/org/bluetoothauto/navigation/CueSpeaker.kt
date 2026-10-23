// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.util.Locale

/** One spoken cue at a time. No queued backlog, microphone, or independent player. */
class CueSpeaker(private val context:Context,private val busy:(Boolean)->Unit,private val error:()->Unit):Closeable {
    private val main=Handler(Looper.getMainLooper())
    private var engine:TextToSpeech?=null
    private var ready=false
    private var closed=false
    private var pending:String?=null
    private var active:String?=null
    private var sequence=0
    private val timeout=Runnable{cancel()}
    fun speak(text:String){
        if(closed)return
        pending=text.take(400)
        if(engine==null){
            engine=TextToSpeech(context.applicationContext){status->main.post{
                if(closed)return@post
                ready=status==TextToSpeech.SUCCESS
                val t=engine
                if(!ready || t==null){pending=null;t?.shutdown();engine=null;error();return@post}
                val supported=t.setLanguage(Locale.getDefault())
                if(supported<0){ready=false;pending=null;t.shutdown();engine=null;error();return@post}
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                // Prefer an installed voice that does not send cues to a network synthesizer.
                t.voices?.firstOrNull{!it.isNetworkConnectionRequired && it.locale.language==Locale.getDefault().language && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)}?.let{t.voice=it}
                t.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                    override fun onStart(id:String?){}
                    override fun onDone(id:String?){main.post{finish(id)}}
                    @Deprecated("TTS callback") override fun onError(id:String?){main.post{if(id==active){finish(id);error()}}}
                    override fun onStop(id:String?,interrupted:Boolean){main.post{finish(id)}}
                })
                playPending()
            }}
        }else if(ready)playPending()
    }
    private fun playPending(){
        val text=pending ?: return;pending=null
        val id="cue-${++sequence}";val wasBusy=active!=null;active=id
        if(!wasBusy)busy(true)
        main.removeCallbacks(timeout);main.postDelayed(timeout,20_000)
        if(engine?.speak(text,TextToSpeech.QUEUE_FLUSH,null,id)!=TextToSpeech.SUCCESS){finish(id);error()}
    }
    private fun finish(id:String?){if(id!=null && id==active){active=null;main.removeCallbacks(timeout);busy(false)}}
    fun cancel(){pending=null;val id=active;engine?.stop();finish(id)}
    override fun close(){if(closed)return;closed=true;cancel();engine?.shutdown();engine=null;main.removeCallbacksAndMessages(null)}
}

/** Distance refreshes do not cause the same instruction to interrupt a song repeatedly. */
internal class CuePolicy {
    private var last=""
    fun accept(instruction:String?):Boolean {
        val key=instruction?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if(key.isBlank()){last="";return false}
        if(key==last)return false
        last=key;return true
    }
    fun reset(){last=""}
}
