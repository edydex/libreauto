// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.io.File;
import java.io.Closeable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One lazy system-voice binding shared by navigation and explicit message Read.
 * Only synthesizeToFile is used; the owning dashboard sends PCM to the car.
 * No text/audio is logged or retained after completion/cancel. */
final class NavigationSpeech implements Closeable {
    interface Listener { void onAudioFile(File file); void onStatus(String status); void onCancelled(); }
    private static final int MAX_TEXT=2000,MAX_FILE=4*1024*1024;
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final File cache;
    private final Listener listener;
    private final Map<String,File> files=new ConcurrentHashMap<>();
    private TextToSpeech engine;
    private String pending;
    private boolean ready,closed;
    private long generation,engineGeneration;
    private Runnable expiry;
    NavigationSpeech(Context context,Listener listener) {
        app=context.getApplicationContext();this.listener=listener;
        cache=new File(app.getCacheDir(),"navigation-speech-"+UUID.randomUUID());
    }
    void speak(String text) {
        if(closed)return;
        cancel();
        if(text==null||text.isBlank()||text.length()>MAX_TEXT) { listener.onStatus("Speech text is empty or too long");return; }
        pending=text;long token=generation;
        expiry=()->{ if(!closed&&generation==token) { cancel();listener.onStatus("Speech ended at its time limit"); } };
        main.postDelayed(expiry,60_000);
        if(engine==null) {
            try { initialize(); } catch(RuntimeException error) { unavailable();return; }
        }
        if(ready)submit();
    }
    private void initialize() {
        long owner=++engineGeneration;
        engine=new TextToSpeech(app,status->main.post(()->{
            if(closed||engine==null||engineGeneration!=owner)return;
            if(status!=TextToSpeech.SUCCESS) { unavailable();return; }
            Voice selected=null;
            try {
                java.util.Set<Voice> voices=engine.getVoices();
                if(voices!=null)for(Voice voice:voices)if(!voice.isNetworkConnectionRequired()&&voice.getFeatures()!=null&&
                    !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)&&"en".equals(voice.getLocale().getLanguage())) {
                    if(selected==null||Locale.US.equals(voice.getLocale()))selected=voice;
                    if(Locale.US.equals(voice.getLocale()))break;
                }
                if(selected==null||engine.setVoice(selected)!=TextToSpeech.SUCCESS) { unavailable();return; }
                engine.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                ready=true;submit();
            } catch(RuntimeException error) { unavailable(); }
        }));
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {
                File file=files.remove(id);if(file==null)return;
                long token;
                try { token=Long.parseLong(id); } catch(NumberFormatException error) { file.delete();return; }
                main.post(()->{
                    if(closed||generation!=token||file.length()<=44||file.length()>MAX_FILE) { file.delete();return; }
                    clearExpiry();
                    try { listener.onAudioFile(file); } catch(RuntimeException error) { file.delete();listener.onStatus("Speech playback is unavailable"); }
                });
            }
            @Override public void onError(String id) { failed(id); }
            @Override public void onStop(String id,boolean interrupted) { discard(id); }
            private void failed(String id) {
                if(discard(id))main.post(()->{
                    if(!closed&&id.equals(Long.toString(generation))) { cancel();listener.onStatus("System voice synthesis is unavailable"); }
                });
            }
            private boolean discard(String id) { File file=files.remove(id);if(file==null)return false;file.delete();return true; }
        });
    }
    private void submit() {
        if(closed||!ready||pending==null)return;
        String text=pending;pending=null;
        if(!cache.isDirectory()&&!cache.mkdirs()) { cancel();listener.onStatus("Speech cache is unavailable");return; }
        String id=Long.toString(generation);File file=new File(cache,id+".wav");files.put(id,file);
        if(engine.synthesizeToFile(text,new Bundle(),file,id)!=TextToSpeech.SUCCESS) {
            files.remove(id);file.delete();cancel();listener.onStatus("System voice synthesis is unavailable");
        }
    }
    private void unavailable() {
        ready=false;cancel();engineGeneration++;
        if(engine!=null)engine.shutdown();engine=null;
        listener.onStatus("Install an offline English voice in Android voice settings");
    }
    private void clearExpiry() { if(expiry!=null)main.removeCallbacks(expiry);expiry=null; }
    void cancel() {
        generation++;pending=null;clearExpiry();
        if(engine!=null)engine.stop();
        for(File file:files.values())file.delete();files.clear();
        listener.onCancelled();
    }
    @Override public void close() {
        if(closed)return;closed=true;engineGeneration++;cancel();
        if(engine!=null)engine.shutdown();engine=null;ready=false;
        File[] remaining=cache.listFiles();if(remaining!=null)for(File file:remaining)file.delete();cache.delete();
    }
}
