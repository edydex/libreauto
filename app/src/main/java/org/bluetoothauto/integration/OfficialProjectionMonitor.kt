// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Observe actual official AA only while requested by a live player owner.
 * Registration and callbacks are main-thread only; every provider/package IPC is on the worker.
 * No car dependency, polling, Wi-Fi inference, preference mutation or startup receiver. */
object OfficialProjectionMonitor {
    enum class Status { UNOBSERVED, QUERYING, PROJECTED, NOT_PROJECTED, UNAVAILABLE, TIMED_OUT }
    private const val TIMEOUT_MS = 1_500L
    private const val MIN_QUERY_INTERVAL_MS = 500L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val listeners = linkedMapOf<Long, (Boolean) -> Unit>()
    private var nextListener = 0L
    private var engine: Engine? = null
    @Volatile private var known = false
    @Volatile private var active = false
    @Volatile private var status = Status.UNOBSERVED
    @Volatile private var queries = 0L
    // Cancellation can call a remote provider. It must never block a service/UI shutdown.
    // At most one cancellation worker + one queued cancellation; idle threads expire.
    private val cancellationWorker by lazy {
        ThreadPoolExecutor(0, 1, 2, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "LibreAuto-AA-query-cancel").apply { isDaemon = true } })
    }

    fun cacheActive(): Boolean = active
    fun cacheKnown(): Boolean = known
    /** Content-free diagnostic; unavailable is distinct from a confirmed disconnected result. */
    fun status(): Status = status
    fun queryCount(): Long = queries

    fun start(context: Context, listener: (Boolean) -> Unit): Closeable {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Start official projection monitoring on the main thread" }
        val id = ++nextListener
        listeners[id] = listener
        // Report the cheap cache immediately. A query result later supplies actual knowledge.
        runCatching { listener(active) }
        if (engine == null) engine = Engine(context.applicationContext).also { it.start() }
        val closed = AtomicBoolean()
        return Closeable {
            if (closed.compareAndSet(false, true)) {
                val remove = {
                    listeners.remove(id)
                    if (listeners.isEmpty()) {
                        engine?.close(); engine = null
                        known = false; active = false
                        status = Status.UNOBSERVED
                        queries = 0
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) remove() else main.post { remove() }
            }
        }
    }

    private fun cancel(signal: CancellationSignal) {
        try { cancellationWorker.execute { runCatching(signal::cancel) } }
        catch (_: RejectedExecutionException) { /* Logical cancellation already rejects the result. */ }
    }

    private class Engine(private val app: Context) : Closeable {
        private val observation = OfficialProjectionObservation()
        private val worker = ThreadPoolExecutor(1, 1, 2, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "LibreAuto-AA-connection-query").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
        private var closed = false
        private var registered = false
        private var pending = false
        private var lastStarted = -MIN_QUERY_INTERVAL_MS
        private var inFlight: Query? = null
        private val deferredQuery = Runnable { if (!closed) request() }
        private class Query(val token: Long) {
            val signal = CancellationSignal()
            val cancelled = AtomicBoolean()
            lateinit var deadline: Runnable
        }
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == OfficialProjectionProtocol.ACTION_UPDATED) request()
                // Intent extras deliberately never participate in connection state.
            }
        }

        fun start() {
            try {
                ContextCompat.registerReceiver(app, receiver, IntentFilter(OfficialProjectionProtocol.ACTION_UPDATED), ContextCompat.RECEIVER_EXPORTED)
                registered = true
            } catch (_: RuntimeException) { /* The initial bounded query still supplies a snapshot. */ }
            request()
        }

        private fun request() {
            if (closed) return
            if (inFlight != null) { pending = true; return }
            val remaining = MIN_QUERY_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastStarted)
            if (remaining > 0) {
                pending = true
                main.removeCallbacks(deferredQuery); main.postDelayed(deferredQuery, remaining)
                return
            }
            main.removeCallbacks(deferredQuery)
            pending = false; lastStarted = SystemClock.elapsedRealtime()
            status = Status.QUERYING
            queries++
            val query = Query(observation.begin())
            inFlight = query
            query.deadline = Runnable {
                if (!closed && inFlight === query && query.cancelled.compareAndSet(false, true)) {
                    status = Status.TIMED_OUT
                    cancel(query.signal)
                }
                // Preserve last confirmed active state; a timeout is not a disconnect.
            }
            main.postDelayed(query.deadline, TIMEOUT_MS)
            try {
                worker.execute {
                    val result = try { read(query.signal) } catch (_: RuntimeException) { null }
                    main.post {
                        if (closed || inFlight !== query) return@post
                        main.removeCallbacks(query.deadline); inFlight = null
                        if (!query.cancelled.get()) {
                            status = when (result) { true -> Status.PROJECTED; false -> Status.NOT_PROJECTED; null -> Status.UNAVAILABLE }
                            if (observation.accept(query.token, result)) publish()
                        }
                        if (pending) request()
                    }
                }
            } catch (_: RejectedExecutionException) {
                main.removeCallbacks(query.deadline); inFlight = null
                status = Status.UNAVAILABLE
            }
        }

        @Suppress("DEPRECATION")
        private fun read(signal: CancellationSignal): Boolean? {
            signal.throwIfCanceled()
            val provider = app.packageManager.resolveContentProvider(OfficialProjectionProtocol.AUTHORITY, 0) ?: return null
            if (provider.packageName != OfficialProjectionProtocol.PROVIDER_PACKAGE ||
                !provider.enabled || !provider.applicationInfo.enabled || !provider.exported) return null
            signal.throwIfCanceled()
            return app.contentResolver.query(Uri.parse(OfficialProjectionProtocol.URI),
                arrayOf(OfficialProjectionProtocol.STATE_COLUMN), null, null, null, signal)?.use { cursor ->
                val column = cursor.getColumnIndex(OfficialProjectionProtocol.STATE_COLUMN)
                if (column < 0 || !cursor.moveToFirst() || cursor.isNull(column)) null
                else OfficialProjectionProtocol.projected(provider.packageName, cursor.getInt(column))
            }
        }

        private fun publish() {
            if (closed || engine !== this) return
            known = observation.known; active = observation.active
            listeners.values.toList().forEach { callback -> runCatching { callback(active) } }
        }

        override fun close() {
            if (closed) return
            closed = true; observation.close()
            main.removeCallbacks(deferredQuery)
            inFlight?.let { query ->
                main.removeCallbacks(query.deadline)
                query.cancelled.set(true); cancel(query.signal)
            }
            inFlight = null; pending = false
            if (registered) runCatching { app.unregisterReceiver(receiver) }
            registered = false
            worker.shutdownNow()
        }
    }
}
