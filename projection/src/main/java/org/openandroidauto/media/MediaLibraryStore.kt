package org.openandroidauto.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.openandroidauto.integration.SharedLibraryIntegration

/** Selected SAF list; release only removed exact library URIs that still have a persisted read grant. */
object MediaLibraryStore {
    const val ACTION_CHANGED = "org.openandroidauto.MEDIA_LIBRARY_CHANGED"
    private const val PREFS = "media-library"

    fun read(context: Context): List<Uri> {
        SharedLibraryIntegration.delegate?.let { return it.mediaQueue(context) }
        return try {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("queue", "[]") ?: "[]")
        (0 until minOf(array.length(), 512)).mapNotNull {
            Uri.parse(array.optString(it)).takeIf { uri -> uri.scheme == "content" }
        }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun replace(context: Context, selected: List<Uri>) {
        require(selected.size <= 512)
        SharedLibraryIntegration.delegate?.let {
            // The host owns persisted grants across every saved playlist and folder.
            // Clearing a selected queue must never revoke another playlist's access.
            it.replaceMediaQueue(context, selected)
            return
        }
        val replacement = selected.distinct()
        val array = JSONArray()
        replacement.forEach { uri ->
            require(uri.scheme == "content")
            array.put(uri.toString())
        }
        val previous = read(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("queue", array.toString()).apply()
        val resolver = context.contentResolver
        val persistedReads = try {
            resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }.toSet()
        } catch (_: SecurityException) {
            emptySet()
        } catch (_: IllegalArgumentException) {
            emptySet()
        }
        for (uri in removedPersistedReads(previous, replacement, persistedReads)) {
            try { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* Session-only/already revoked grants need no release. */ }
            catch (_: IllegalArgumentException) { /* A removed provider may no longer accept this URI. */ }
        }
    }

    fun clear(context: Context) = replace(context, emptyList())

    internal fun <T> removedPersistedReads(previous: Collection<T>, replacement: Collection<T>, persistedReadable: Set<T>): Set<T> =
        previous.toSet().subtract(replacement.toSet()).intersect(persistedReadable)
}
