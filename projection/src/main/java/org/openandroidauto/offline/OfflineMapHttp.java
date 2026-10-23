// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.maplibre.android.module.http.HttpRequestUtil;
import org.openandroidauto.BuildConfig;
import org.openandroidauto.ServiceState;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scoped public-provider downloader transport. Ordinary driving keeps the SDK's default client. */
final class OfflineMapHttp implements Closeable {
    private static final String PREFERENCES="offline-map-request-budgets-v1";
    private static OfflineMapHttp owner;
    private final OkHttpClient client;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean limited=new AtomicBoolean();
    private final AtomicBoolean paused=new AtomicBoolean();
    private final AtomicBoolean projectionInterrupted=new AtomicBoolean();
    private final NetworkCleanup cleanup;
    private volatile boolean closed;

    static synchronized OfflineMapHttp start(Context context,String area,Runnable onLimit,Runnable onProjection) {
        if(owner!=null)throw new IllegalStateException("Another map download is still closing");
        OfflineMapHttp lease=new OfflineMapHttp(context,area,onLimit,onProjection);
        HttpRequestUtil.setOkHttpClient(lease.client);
        owner=lease;
        return lease;
    }
    static long requestsUsed(Context context,String area) {
        try { return context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).getLong(area,0); }
        catch(RuntimeException error) { return Long.MAX_VALUE; }
    }
    static void forget(Context context,String area) {
        context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit().remove(area).apply();
    }
    private OfflineMapHttp(Context context,String area,Runnable onLimit,Runnable onProjection) {
        SharedPreferences preferences=context.getApplicationContext().getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
        OfflineRequestBudget budget=new OfflineRequestBudget(new OfflineRequestBudget.Backend() {
            public long used() { try { return preferences.getLong(area,0); } catch(RuntimeException error) { return Long.MAX_VALUE; } }
            public boolean store(long value) { return preferences.edit().putLong(area,value).commit(); }
        });
        Dispatcher dispatcher=new Dispatcher();
        dispatcher.setMaxRequests(4);dispatcher.setMaxRequestsPerHost(4);
        client=new OkHttpClient.Builder().dispatcher(dispatcher).retryOnConnectionFailure(false)
                .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
                .callTimeout(45,TimeUnit.SECONDS).addNetworkInterceptor(chain -> {
                    if(paused.get())throw new IOException("Offline download paused");
                    if(ServiceState.INSTANCE.getProjectionActive()) {
                        if(projectionInterrupted.compareAndSet(false,true))main.post(() -> { if(!closed)onProjection.run(); });
                        throw new IOException("Car connection supersedes offline download");
                    }
                    if(!budget.reserve()) {
                        if(limited.compareAndSet(false,true))main.post(() -> { if(!closed)onLimit.run(); });
                        throw new IOException("Offline area request limit reached");
                    }
                    Request request=chain.request().newBuilder().header("User-Agent",
                            "OpenAutoLite/"+BuildConfig.VERSION_NAME+" OfflineMaps/1 (+https://github.com/edydex/hibreak-auto-scale)").build();
                    return chain.proceed(request);
                }).build();
        cleanup=new NetworkCleanup(() -> client.dispatcher().cancelAll(),() -> {
            try { client.connectionPool().evictAll(); }
            finally { client.dispatcher().executorService().shutdown(); }
        });
    }
    void pauseRequests() { paused.set(true);cleanup.cancel(); }
    @Override public void close() {
        synchronized(OfflineMapHttp.class) {
            if(closed)return;
            closed=true;paused.set(true);
            if(owner==this) { HttpRequestUtil.setOkHttpClient(null);owner=null; }
        }
        main.removeCallbacksAndMessages(null);
        cleanup.close();
    }

    /** Cancellation can close a TLS socket, too. Serialize every network teardown off Main. */
    static final class NetworkCleanup {
        private final Runnable cancel,finish;
        private final ExecutorService worker=Executors.newSingleThreadExecutor(task -> {
            Thread thread=new Thread(task,"OpenAutoOfflineCleanup");thread.setDaemon(true);return thread;
        });
        private boolean closing;
        NetworkCleanup(Runnable cancel,Runnable finish) { this.cancel=cancel;this.finish=finish; }
        synchronized void cancel() {
            if(closing)return;
            worker.execute(() -> safely(cancel));
        }
        synchronized void close() {
            if(closing)return;
            closing=true;
            worker.execute(() -> { safely(cancel);safely(finish); });
            // Both previously queued cancellation and final cleanup run before this worker exits.
            worker.shutdown();
        }
        boolean awaitTermination(long timeout,TimeUnit unit) throws InterruptedException {
            return worker.awaitTermination(timeout,unit);
        }
        private static void safely(Runnable action) {
            try { action.run(); }
            catch(RuntimeException ignored) { /* Still attempt the remaining owned teardown. */ }
        }
    }
}
