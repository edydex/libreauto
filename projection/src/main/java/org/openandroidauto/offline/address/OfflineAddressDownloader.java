// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.offline.address;

import android.content.Context;
import android.os.CancellationSignal;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.openandroidauto.BuildConfig;
import org.openandroidauto.offline.OfflineAreaPolicy;

/** One explicit area POST. Local searches never use this endpoint or send their query text. */
public final class OfflineAddressDownloader {
    public static final String DEFAULT_ENDPOINT="https://overpass.private.coffee/api/interpreter";
    private static final Semaphore DOWNLOAD=new Semaphore(1);
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
            .callTimeout(90,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private OfflineAddressDownloader() {}
    public interface Progress { void onProgress(long bytes,int entries); }
    /** Worker only. CancellationSignal belongs to this phase while this method is running. */
    public static OfflineAddressIndex.AreaStatus fetchAndReplace(Context context,String areaId,OfflineAreaPolicy.Bounds bounds,CancellationSignal cancel,Progress progress) throws IOException {
        UUID.fromString(areaId);OfflineAreaPolicy.validate(bounds.north,bounds.east,bounds.south,bounds.west);
        OfflineAddressParser.active(cancel);
        if(!DOWNLOAD.tryAcquire())throw new IOException("An address-area download is already running");
        ExecutorService cleanup=Executors.newSingleThreadExecutor(task -> { Thread thread=new Thread(task,"OfflineAddressCancel");thread.setDaemon(true);return thread; });
        try {
            Request request=new Request.Builder().url(DEFAULT_ENDPOINT).header("User-Agent","OpenAutoLite/"+BuildConfig.VERSION_NAME+" OfflineAddress/1 (+https://github.com/edydex/hibreak-auto-scale)")
                    .header("Accept","application/json").post(new FormBody.Builder().add("data",query(bounds)).build()).build();
            Call call=HTTP.newCall(request);
            // OkHttp cancellation can close a TLS socket. Do not perform that teardown
            // inside Android's caller-thread cancellation listener (often the UI thread).
            if(cancel!=null)cancel.setOnCancelListener(() -> cleanup.execute(call::cancel));
            try {
                OfflineAddressParser.active(cancel);if(progress!=null)progress.onProgress(0,0);
                try(Response response=call.execute()) {
                    if(response.code()!=200)throw new IOException("Address provider returned HTTP "+response.code()+"; try again later");
                    if(response.body()==null)throw new IOException("The address provider returned no data");
                    if(response.body().contentLength()>OfflineAddressInput.MAX_BYTES)throw new IOException("Address data is too large; choose a smaller area");
                    return OfflineAddressIndex.open(context).replaceArea(areaId,bounds,System.currentTimeMillis(),response.body().byteStream(),cancel,progress);
                }
            } catch(InterruptedIOException error) {
                throw timeoutFailure(error,(cancel!=null&&cancel.isCanceled())||Thread.currentThread().isInterrupted());
            } finally { if(cancel!=null)cancel.setOnCancelListener(null); }
        } finally { cleanup.shutdown();DOWNLOAD.release(); }
    }
    static IOException timeoutFailure(InterruptedIOException cause,boolean cancelled) {
        return new IOException(cancelled?"Address download cancelled":"Address download timed out. Resume to try again.",cause);
    }
    public static String query(OfflineAreaPolicy.Bounds bounds) {
        OfflineAreaPolicy.validate(bounds.north,bounds.east,bounds.south,bounds.west);
        String box=String.format(Locale.US,"(%.10f,%.10f,%.10f,%.10f)",bounds.south,bounds.west,bounds.north,bounds.east);
        StringBuilder query=new StringBuilder("[out:json][timeout:60][maxsize:67108864];(");
        query.append("nwr[\"addr:housenumber\"]").append(box).append(';');
        query.append("way[\"highway\"][\"name\"]").append(box).append(';');
        for(String key:new String[]{"amenity","shop","tourism","leisure","office","healthcare","place"})query.append("nwr[\"name\"][\"").append(key).append("\"]").append(box).append(';');
        return query.append(");out body center qt ").append(OfflineAddressParser.MAX_ELEMENTS+1).append(";out count;").toString();
    }
}
