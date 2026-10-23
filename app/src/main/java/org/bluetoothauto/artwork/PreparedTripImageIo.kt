// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One bounded disk/PNG worker. Every bitmap handed to save becomes this worker's private property. */
internal class PreparedTripImageIo(directory: File, private val main: Handler) : AutoCloseable {
    private val store = PreparedTripImageStore(directory)
    private val queue = LinkedBlockingDeque<Runnable>(8)
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue,
        { task -> Thread(task, "prepared-trip-images").apply { isDaemon = true } })
    private val generation = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var trip: String? = null
    private var workerTrip: String? = null
    init { executor.prestartCoreThread() }

    private class Work(val runWork: () -> Unit, val cancelWork: () -> Unit) : Runnable {
        override fun run() = runWork()
        fun cancel() = cancelWork()
    }

    fun activate(fingerprint: String?) {
        trip = fingerprint; generation.incrementAndGet(); cancelQueued()
        if (fingerprint == null || closed) return
        val token = generation.get()
        offer(Work({
            if (valid(token, fingerprint)) try { select(fingerprint) } catch (_: Exception) { }
        }, {}), priority = true)
    }

    fun load(key: PreparedTripImageKey, priority: Boolean, callback: (Bitmap?) -> Unit) {
        val token = generation.get()
        fun deliver(bitmap: Bitmap?) { main.post {
            if (valid(token, key.trip)) callback(bitmap) else { bitmap?.recycle(); callback(null) }
        } }
        val work = Work({
            var bitmap: Bitmap? = null
            try {
                if (valid(token, key.trip)) {
                    select(key.trip)
                    val bytes = store.load(key)
                    if (bytes != null && valid(token, key.trip)) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth == 512 && bounds.outHeight == 512) {
                            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                            bitmap?.density = Bitmap.DENSITY_NONE
                        }
                    }
                }
            } catch (_: Exception) { bitmap?.recycle(); bitmap = null }
            catch (_: OutOfMemoryError) { bitmap?.recycle(); bitmap = null }
            deliver(bitmap)
        }, { deliver(null) })
        offer(work, priority)
    }

    fun save(key: PreparedTripImageKey, ownedBitmap: Bitmap, callback: (PreparedTripImageStore.SaveResult) -> Unit) {
        val token = generation.get()
        fun deliver(result: PreparedTripImageStore.SaveResult) { main.post {
            callback(if (valid(token, key.trip)) result else PreparedTripImageStore.SaveResult(false))
        } }
        offer(Work({
            var result = PreparedTripImageStore.SaveResult(false)
            try {
                if (valid(token, key.trip) && !ownedBitmap.isRecycled) {
                    val bytes = BoundedPng()
                    if (ownedBitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes) && valid(token, key.trip)) {
                        select(key.trip)
                        result = store.saveResult(key, bytes.toByteArray())
                    }
                }
            } catch (_: Exception) { }
            catch (_: OutOfMemoryError) { }
            finally { if (!ownedBitmap.isRecycled) ownedBitmap.recycle() }
            deliver(result)
        }, { if (!ownedBitmap.isRecycled) ownedBitmap.recycle(); deliver(PreparedTripImageStore.SaveResult(false)) }), priority = false)
    }

    private fun select(fingerprint: String) {
        if (workerTrip != fingerprint) { store.activate(fingerprint); workerTrip = fingerprint }
    }
    private fun valid(token: Long, fingerprint: String) = !closed && generation.get() == token && trip == fingerprint
    private fun offer(work: Work, priority: Boolean) {
        if (closed) { work.cancel(); return }
        if (priority && queue.remainingCapacity() == 0) (queue.pollLast() as? Work)?.cancel()
        if (!(if (priority) queue.offerFirst(work) else queue.offerLast(work))) work.cancel()
    }
    private fun cancelQueued() { while (true) (queue.pollFirst() as? Work ?: break).cancel() }
    override fun close() {
        if (closed) return
        closed = true; generation.incrementAndGet(); cancelQueued(); executor.shutdownNow()
    }
    private class BoundedPng : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        override fun write(value: Int) { check(bytes.size() < PreparedTripImageStore.MAX_IMAGE_BYTES); bytes.write(value) }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            check(length <= PreparedTripImageStore.MAX_IMAGE_BYTES - bytes.size()); bytes.write(value, offset, length)
        }
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}
