package org.openandroidauto.wireless

import android.content.Context
import android.os.SystemClock
import org.openandroidauto.transport.WirelessCarTransport

/** App-private, bounded connection stages for an untethered parked-car test. No free-text data. */
class WirelessConnectionReport private constructor(private val context: Context, private val owner: Long) {
    enum class Projection { STARTED, AUTHENTICATING, ACTIVE, ENDED, FAILED, WRITE_FAILED, HANDSHAKE_TIMED_OUT }
    private val started = SystemClock.elapsedRealtime()
    private val lines = ArrayList<String>()

    fun record(status: WirelessCarTransport.Status) = append("B", status.name)
    fun record(stage: Projection) = append("P", stage.name)

    private fun append(domain: String, name: String) = synchronized(lock) {
        if (owner != currentOwner) return@synchronized
        val elapsed = (SystemClock.elapsedRealtime() - started).coerceIn(0, MAX_ELAPSED_MS)
        val key = "$domain $name"
        if (lines.lastOrNull()?.substringBeforeLast(' ') == key) return@synchronized
        lines.add("$key $elapsed")
        while (lines.size > MAX_ENTRIES) lines.removeAt(0)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("stages", lines.joinToString("\n")).apply()
    }

    companion object {
        private const val PREFERENCES = "wireless-connection-report"
        internal const val MAX_ENTRIES = 40
        internal const val MAX_ELAPSED_MS = 30L * 24 * 60 * 60 * 1000
        private val lock = Any()
        private var currentOwner = 0L

        fun begin(context: Context): WirelessConnectionReport = synchronized(lock) {
            WirelessConnectionReport(context.applicationContext, ++currentOwner).also { it.record(Projection.STARTED) }
        }

        fun read(context: Context): String = format(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString("stages", "").orEmpty())

        /** Validate stored data as well: no arbitrary preference strings reach clipboard/reports. */
        internal fun format(stored: String): String {
            if (stored.length > 8192) return "No valid wireless connection report."
            val safe = stored.lineSequence().take(MAX_ENTRIES).mapNotNull { line ->
                val parts = line.split(' ')
                if (parts.size != 3) return@mapNotNull null
                val elapsed = parts[2].toLongOrNull()?.takeIf { it in 0..MAX_ELAPSED_MS } ?: return@mapNotNull null
                val stage = when (parts[0]) {
                    "B" -> try { WirelessCarTransport.Status.valueOf(parts[1]).name } catch (_: IllegalArgumentException) { null }
                    "P" -> try { Projection.valueOf(parts[1]).name } catch (_: IllegalArgumentException) { null }
                    else -> null
                } ?: return@mapNotNull null
                "${elapsed}ms ${if (parts[0] == "B") "connection" else "projection"}: $stage"
            }.toList()
            return if (safe.isEmpty()) "No wireless connection report yet."
                else "LibreAuto experimental wireless connection\n" + safe.joinToString("\n") +
                    "\nStages only; no device addresses, network names, credentials, location or conversation text."
        }
    }
}
