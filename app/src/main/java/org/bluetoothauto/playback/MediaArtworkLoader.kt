// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.bluetoothauto.model.Track
import java.io.Closeable
import java.io.FileNotFoundException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Tags and artwork are process-local presentation data, never written back to the music file. */
data class TrackPresentation(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: Bitmap? = null,
    val error: String? = null
)

/**
 * One bounded metadata worker, a memory-only LRU, and main-thread callbacks.
 * A request handle cancels its callback, not a native operation or another subscriber.
 * Returned artwork belongs to the shared cache: consumers must not mutate or recycle it.
 *
 * Android's retriever returns embedded art as a complete byte array. The 8 MiB input
 * limit is applied immediately afterward; the API cannot prevent that first allocation.
 * Native calls also cannot safely be killed. A five-second UI deadline returns a
 * fallback, but the sole worker remains occupied until native work returns and finally
 * closes its retriever/descriptor. No replacement threads are spawned for timeouts.
 */
class MediaArtworkLoader(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1), { job -> Thread(job, "BluetoothAuto-music-tags").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy())
    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)
    private val waiting = LinkedHashMap<String, Job>()
    private var cacheBytes = 0L
    private var active: Job? = null
    @Volatile private var closed = false

    private data class Cached(val value: TrackPresentation, val expiresAt: Long, val bytes: Long)
    private class Subscriber(val callback: (TrackPresentation) -> Unit) : Closeable {
        val cancelled = AtomicBoolean(false)
        override fun close() { cancelled.set(true) }
    }
    private class Job(val track: Track, val subscribers: MutableList<Subscriber>) {
        var timeout: Runnable? = null
        var timedOut = false
    }

    /** Always delivers asynchronously on main; closing the returned handle suppresses delivery. */
    fun load(track: Track, callback: (TrackPresentation) -> Unit): Closeable {
        val subscriber = Subscriber(callback)
        main.post { if (!closed && !subscriber.cancelled.get()) enqueue(track, subscriber) }
        return subscriber
    }

    /** Main-thread cache lookup. Entries and their bitmaps are shared and must not be recycled. */
    fun cached(uri: String): TrackPresentation? {
        requireMain()
        val item = cache[uri] ?: return null
        if (item.expiresAt <= SystemClock.uptimeMillis()) { removeCached(uri); return null }
        return item.value
    }

    /** Drop memory-only tags/art, for example after the user replaces a file in place. */
    fun invalidate(uri: String? = null) {
        requireMain()
        if (uri == null) { cache.clear(); cacheBytes = 0 } else removeCached(uri)
    }

    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { close() }; return }
        if (closed) return
        closed = true
        active?.let { job ->
            job.timeout?.let(main::removeCallbacks)
            job.subscribers.forEach(Subscriber::close); job.subscribers.clear()
        }
        waiting.values.forEach { job ->
            job.timeout?.let(main::removeCallbacks)
            job.subscribers.forEach(Subscriber::close); job.subscribers.clear()
        }
        waiting.clear(); cache.clear(); cacheBytes = 0
        // Interruptions are advisory for native retriever calls; finally owns resource closure.
        worker.shutdownNow()
    }

    private fun enqueue(track: Track, subscriber: Subscriber) {
        if (localUri(track.uri) == null) {
            deliver(subscriber, fallback(track, "Choose a local audio file again.")); return
        }
        cached(track.uri)?.let { deliver(subscriber, it); return }
        val existing = active?.takeIf { it.track.uri == track.uri } ?: waiting[track.uri]
        if (existing != null) {
            if (existing.timedOut) deliver(subscriber, fallback(track, "Music details timed out. Playback is still available."))
            else if (existing.subscribers.size < MAX_SUBSCRIBERS) existing.subscribers.add(subscriber)
            else deliver(subscriber, fallback(track, "Music details are busy. Playback is still available."))
            return
        }
        if (waiting.size >= MAX_WAITING) {
            deliver(subscriber, fallback(track, "Music details are busy. Playback is still available.")); return
        }
        val job = Job(track, mutableListOf(subscriber))
        job.timeout = Runnable { timeout(job) }.also { main.postDelayed(it, DEADLINE_MS) }
        waiting[track.uri] = job
        startNext()
    }

    private fun startNext() {
        if (closed || active != null) return
        while (waiting.isNotEmpty()) {
            val entry = waiting.entries.first()
            val job = entry.value
            waiting.remove(entry.key)
            if (job.subscribers.all { it.cancelled.get() }) {
                job.timeout?.let(main::removeCallbacks); continue
            }
            active = job
            try {
                worker.execute {
                    val value = read(job.track)
                    main.post { finish(job, value) }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                finish(job, fallback(job.track, "Music details are unavailable."))
            }
            return
        }
    }

    private fun timeout(job: Job) {
        if (closed || job.timedOut) return
        if (active !== job && waiting[job.track.uri] !== job) return
        job.timedOut = true
        waiting.remove(job.track.uri)
        val value = fallback(job.track, "Music details timed out. Playback is still available.")
        remember(value)
        val listeners = job.subscribers.toList(); job.subscribers.clear()
        listeners.forEach { deliver(it, value) }
        if (active !== job) startNext()
    }

    private fun finish(job: Job, value: TrackPresentation) {
        if (active !== job) return
        active = null
        job.timeout?.let(main::removeCallbacks)
        if (closed) return
        remember(value)
        if (!job.timedOut) {
            val listeners = job.subscribers.toList(); job.subscribers.clear()
            listeners.forEach { deliver(it, value) }
        }
        startNext()
    }

    private fun read(track: Track): TrackPresentation {
        var retriever: MediaMetadataRetriever? = null
        var descriptor: AssetFileDescriptor? = null
        var value = fallback(track)
        try {
            if (Thread.currentThread().isInterrupted) return value.copy(error = "Music details were cancelled.")
            val uri = localUri(track.uri) ?: return value.copy(error = "Unsupported audio file.")
            descriptor = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw FileNotFoundException()
            val reader = MediaMetadataRetriever()
            retriever = reader
            if (descriptor.declaredLength >= 0) reader.setDataSource(descriptor.fileDescriptor,
                descriptor.startOffset, descriptor.declaredLength)
            else if (descriptor.startOffset == 0L) reader.setDataSource(descriptor.fileDescriptor)
            else return value.copy(error = "This file provider cannot expose music details.")
            fun text(key: Int): String? = try { reader.extractMetadata(key) } catch (_: RuntimeException) { null }
            value = TrackPresentation(track.uri,
                MediaArtworkPolicy.title(text(MediaMetadataRetriever.METADATA_KEY_TITLE), track.title),
                MediaArtworkPolicy.clean(text(MediaMetadataRetriever.METADATA_KEY_ARTIST)).ifBlank { MediaArtworkPolicy.clean(track.artist) },
                MediaArtworkPolicy.clean(text(MediaMetadataRetriever.METADATA_KEY_ALBUM)),
                MediaArtworkPolicy.duration(text(MediaMetadataRetriever.METADATA_KEY_DURATION)))
            val bytes = try { reader.embeddedPicture } catch (_: RuntimeException) {
                return value.copy(error = "Album artwork is unavailable.")
            } ?: return value
            if (bytes.size > MediaArtworkPolicy.MAX_EMBEDDED_BYTES) return value.copy(error = "Album artwork is too large.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample = MediaArtworkPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
                ?: return value.copy(error = "Album artwork is unreadable.")
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false; inMutable = false
            }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return value.copy(error = "Album artwork is unreadable.")
            if (bitmap.width > MediaArtworkPolicy.MAX_ART_EDGE || bitmap.height > MediaArtworkPolicy.MAX_ART_EDGE) {
                val scale = MediaArtworkPolicy.MAX_ART_EDGE.toDouble() / maxOf(bitmap.width, bitmap.height)
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            return value.copy(artwork = bitmap)
        } catch (_: SecurityException) { return value.copy(error = "File access was lost. Choose the audio file again.") }
        catch (_: FileNotFoundException) { return value.copy(error = "The audio file is unavailable. Choose it again.") }
        catch (_: OutOfMemoryError) { return value.copy(error = "Album artwork is too large.") }
        catch (_: Exception) { return value.copy(error = "Music details are unavailable for this file.") }
        finally {
            runCatching { retriever?.release() }
            runCatching { descriptor?.close() }
        }
    }

    private fun fallback(track: Track, error: String? = null) = TrackPresentation(track.uri,
        MediaArtworkPolicy.title(null, track.title), MediaArtworkPolicy.clean(track.artist), error = error)

    private fun localUri(raw: String): Uri? {
        if (raw.length !in 1..8192) return null
        val uri = Uri.parse(raw)
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content" -> uri.takeIf { !it.authority.isNullOrBlank() }
            "file" -> uri.takeIf { (it.authority.isNullOrBlank() || it.authority == "localhost") && it.path?.startsWith('/') == true }
            else -> null
        }
    }

    private fun remember(value: TrackPresentation) {
        removeCached(value.uri)
        val bytes = value.artwork?.allocationByteCount?.toLong()?.coerceAtLeast(0) ?: 0
        cache[value.uri] = Cached(value, SystemClock.uptimeMillis() + if (value.error == null) CACHE_MS else ERROR_CACHE_MS, bytes)
        cacheBytes += bytes
        val iterator = cache.entries.iterator()
        while ((cache.size > MAX_CACHE_ENTRIES || cacheBytes > MAX_CACHE_BYTES) && iterator.hasNext()) {
            cacheBytes -= iterator.next().value.bytes; iterator.remove()
        }
    }

    private fun removeCached(uri: String) { cache.remove(uri)?.let { cacheBytes -= it.bytes } }
    private fun deliver(subscriber: Subscriber, value: TrackPresentation) {
        if (!closed && !subscriber.cancelled.get()) subscriber.callback(value)
    }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "Use MediaArtworkLoader on the main thread" } }

    companion object {
        private const val MAX_WAITING = 16
        private const val MAX_SUBSCRIBERS = 16
        private const val MAX_CACHE_ENTRIES = 24
        private const val MAX_CACHE_BYTES = 4L * 1024 * 1024
        private const val DEADLINE_MS = 5_000L
        private const val CACHE_MS = 5L * 60 * 1000
        private const val ERROR_CACHE_MS = 10_000L
    }
}

/** Pure policies cover malformed tags and resource bounds without invoking native metadata code. */
internal object MediaArtworkPolicy {
    const val MAX_EMBEDDED_BYTES = 8 * 1024 * 1024
    const val MAX_ART_EDGE = 512
    private val spaces = Regex("\\s+")
    private val extensions = setOf("mp3", "flac", "ogg", "m4a", "aac", "opus", "wav", "wma", "aiff", "alac", "mp4", "webm", "mkv", "3gp", "amr", "mid", "midi")

    fun clean(raw: String?): String = raw.orEmpty().take(1024).map { if (it.isISOControl()) ' ' else it }
        .joinToString("").replace(spaces, " ").trim().take(160)

    fun title(tag: String?, fallback: String): String {
        clean(tag).takeIf { it.isNotBlank() }?.let { return it }
        val name = clean(fallback)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return (if (extension in extensions) name.substringBeforeLast('.') else name).ifBlank { "Unknown track" }
    }

    fun duration(raw: String?): Long = raw?.toLongOrNull()?.takeIf { it in 1..MAX_DURATION_MS } ?: 0L

    fun sampleSize(width: Int, height: Int): Int? {
        if (width !in 1..65_536 || height !in 1..65_536) return null
        var sample = 1
        while ((maxOf(width, height).toLong() + sample - 1) / sample > MAX_ART_EDGE) sample *= 2
        return sample
    }

    private const val MAX_DURATION_MS = 365L * 24 * 60 * 60 * 1000
}
