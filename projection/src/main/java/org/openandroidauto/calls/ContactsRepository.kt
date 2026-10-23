// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.calls

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded asynchronous provider operation, plus at most one queued replacement. No observer/polling. */
class ContactsRepository(context: Context, private val accessAllowed: () -> Boolean = { CallsAccess.canRead(context) }) : Closeable {
    enum class Failure { ACCESS_REQUIRED, UNAVAILABLE, TIMED_OUT, BUSY }
    data class Result(val rows: List<CallContact> = emptyList(), val failure: Failure? = null, val truncated: Boolean = false)
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { work -> Thread(work, "lite-contact-query") }, ThreadPoolExecutor.AbortPolicy())
    private val cancelling = AtomicBoolean()
    private val canceller = java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work, "lite-contact-cancel") }
    private var active: Operation? = null
    private var closed = false
    private class Operation {
        val signal = CancellationSignal()
        val finished = AtomicBoolean()
        val cancelRequested = AtomicBoolean()
        var future: Future<*>? = null
        var timeout: Runnable? = null
    }

    fun query(query: String, favorites: Boolean, callback: (Result) -> Unit) = request(callback) { signal ->
        val safe = CallPolicy.searchQuery(query) ?: return@request Result(failure = Failure.UNAVAILABLE)
        val uri = if (safe.isEmpty()) Phone.CONTENT_URI else Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(safe))
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(Phone.DISPLAY_NAME_PRIMARY, Phone._ID))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, CallPolicy.MAX_RESULTS + 1)
            if (favorites) {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Phone.STARRED} = ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("1"))
            }
        }
        val rows = ArrayList<CallContact>()
        var truncated = false
        context.contentResolver.query(uri, projection, args, signal)?.use { cursor ->
            var examined = 0
            val seen = HashSet<String>()
            while (cursor.moveToNext()) {
                signal.throwIfCanceled()
                if (++examined > CallPolicy.MAX_RESULTS) { truncated = true; break }
                read(cursor)?.let { row ->
                    if ((!favorites || cursor.getInt(5) != 0) && CallPolicy.matches(row, safe)
                        && seen.add("${row.contactId}/${row.dialNumber}")) rows += row
                }
            }
        } ?: return@request Result(failure = Failure.UNAVAILABLE)
        Result(rows.toList(), truncated = truncated)
    }

    fun revalidate(contact: CallContact, callback: (Result) -> Unit) = request(callback) { signal ->
        val uri = android.content.ContentUris.withAppendedId(Phone.CONTENT_URI, contact.dataId)
        val rows = ArrayList<CallContact>(1)
        context.contentResolver.query(uri, projection, Bundle(), signal)?.use { cursor ->
            if (cursor.moveToNext()) read(cursor)?.let(rows::add)
            if (cursor.moveToNext()) return@request Result(failure = Failure.UNAVAILABLE)
        } ?: return@request Result(failure = Failure.UNAVAILABLE)
        Result(rows.toList())
    }

    private fun request(callback: (Result) -> Unit, work: (CancellationSignal) -> Result) {
        check(Looper.myLooper() == Looper.getMainLooper())
        cancel()
        if (closed) return
        if (!accessAllowed()) { callback(Result(failure = Failure.ACCESS_REQUIRED)); return }
        // A remote provider may ignore/block cancellation. Do not queue more work behind it.
        if (cancelling.get()) { callback(Result(failure = Failure.BUSY)); return }
        val operation = Operation()
        active = operation
        fun deliver(result: Result) {
            if (!operation.finished.compareAndSet(false, true)) return
            main.post {
                operation.timeout?.let(main::removeCallbacks)
                if (active === operation && !closed) { active = null; callback(result) }
            }
        }
        operation.timeout = Runnable {
            cancelOperation(operation)
            deliver(Result(failure = Failure.TIMED_OUT))
        }.also { main.postDelayed(it, 5_000) }
        try {
            worker.purge()
            operation.future = worker.submit {
                val result = try {
                    operation.signal.throwIfCanceled()
                    if (!accessAllowed()) Result(failure = Failure.ACCESS_REQUIRED) else work(operation.signal)
                } catch (_: SecurityException) { Result(failure = Failure.ACCESS_REQUIRED) }
                catch (_: android.os.OperationCanceledException) { Result(failure = Failure.TIMED_OUT) }
                catch (_: RuntimeException) { Result(failure = Failure.UNAVAILABLE) }
                deliver(result)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { deliver(Result(failure = Failure.BUSY)) }
    }

    fun cancel() {
        check(Looper.myLooper() == Looper.getMainLooper())
        active?.let { operation ->
            active = null; operation.finished.set(true)
            operation.timeout?.let(main::removeCallbacks)
            cancelOperation(operation)
        }
    }
    private fun cancelOperation(operation: Operation) {
        operation.future?.cancel(true)
        if (!operation.cancelRequested.compareAndSet(false, true)) return
        if (!cancelling.compareAndSet(false, true)) return
        canceller.execute {
            try { operation.signal.cancel() } finally { cancelling.set(false) }
        }
    }
    override fun close() { cancel(); closed = true; worker.shutdownNow(); canceller.shutdown() }

    private fun read(cursor: android.database.Cursor): CallContact? = try {
        val type = cursor.getInt(6)
        val label = Phone.getTypeLabel(context.resources, type, cursor.getString(7)).toString().ifBlank { "Phone" }
        CallContact(cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3), label, cursor.getString(4))
    } catch (_: IllegalArgumentException) { null }
    companion object {
        private val projection = arrayOf(Phone._ID, Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER, Phone.STARRED, Phone.TYPE, Phone.LABEL)
    }
}
