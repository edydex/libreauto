// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import org.bluetoothauto.model.Track
import java.io.Closeable
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

data class MusicImportResult(val tracks: List<Track>, val sourceName: String,
    val skipped: Int = 0, val truncated: Boolean = false, val playlist: Boolean = false)

/** Blocking read-only work; the Activity runs it on a single cancellable worker. */
class MusicImporter(context: Context) : Closeable {
    private val resolver = context.applicationContext.contentResolver
    @Volatile private var cancelled = false
    @Volatile private var activeQuery: CancellationSignal? = null
    @Volatile private var activeInput: InputStream? = null
    private var deadline = 0L
    private var documentsSeen = 0

    fun files(uris: List<Uri>): MusicImportResult {
        begin()
        val tracks = uris.distinct().take(PlayerQueue.MAX_SIZE).mapNotNull { uri ->
            checkWork()
            if (uri.scheme != "content") null else Track(uri.toString(), displayName(uri)?.take(180) ?: "Audio file")
        }
        return MusicImportResult(tracks, "Audio files", truncated = uris.size > PlayerQueue.MAX_SIZE)
    }

    /** Lists immediate children only. The UI keeps its own parent/breadcrumb stack. */
    fun browse(tree: Uri, documentId: String? = null): MusicFolderListing {
        begin()
        val id = selectDirectory(tree, documentId)
        val name = directoryName(tree, id)
        val children = children(tree, id)
        return MusicFolderPolicy.listing(name, id, children.documents, children.limited) {
            DocumentsContract.buildDocumentUriUsingTree(tree, it).toString()
        }
    }

    fun folder(tree: Uri, startDocumentId: String? = null): MusicImportResult {
        begin()
        val index = index(tree, null, startDocumentId)
        val tracks = index.audio.sortedWith { a, b -> NaturalFileOrder.compare(a.relativePath, b.relativePath) }
            .take(PlayerQueue.MAX_SIZE).map { Track(it.uri, it.name.take(180)) }
        return MusicImportResult(tracks, index.name, truncated = index.limited || index.audio.size > PlayerQueue.MAX_SIZE)
    }

    fun playlist(document: Uri, tree: Uri): MusicImportResult {
        begin()
        require(document.scheme == "content")
        val name = displayName(document) ?: "VLC playlist"
        val bytes = resolver.openInputStream(document)?.use { input ->
            activeInput = input
            try {
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkWork()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > 2 * 1024 * 1024) throw IllegalArgumentException("Playlist is too large")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } finally { activeInput = null }
        } ?: throw java.io.FileNotFoundException()
        val parsed = PlaylistParser.parse(bytes, name)
        checkWork()
        val index = index(tree, document)
        val tracks = ArrayList<Track>()
        var missing = parsed.skipped
        val uriSyntax = name.substringAfterLast('.', "").equals("xspf", true)
        for (entry in parsed.entries) {
            checkWork()
            val audio = PlaylistPathPolicy.resolve(entry.location, index.audio, index.playlistDirectory, index.name, uriSyntax)
            if (audio == null) missing++ else tracks.add(Track(audio.uri,
                entry.title?.takeIf { it.isNotBlank() }?.take(180) ?: audio.name.take(180), entry.artist?.take(180) ?: ""))
        }
        return MusicImportResult(tracks, name.take(180), missing, parsed.truncated || index.limited, true)
    }

    private data class FolderIndex(val name: String, val audio: List<AudioDocument>,
        val playlistDirectory: String, val limited: Boolean)
    private data class Directory(val id: String, val path: String, val depth: Int)
    private data class Children(val documents: List<MusicFolderDocument>, val limited: Boolean)

    private fun selectDirectory(tree: Uri, requestedId: String?): String {
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "Choose a music folder" }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return MusicFolderPolicy.selectDirectory(rootId, requestedId, { target ->
            checkWork()
            if (Build.VERSION.SDK_INT < 29) null else try {
                DocumentsContract.isChildDocument(resolver, root,
                    DocumentsContract.buildDocumentUriUsingTree(tree, target))
            } catch (_: UnsupportedOperationException) { null }
            catch (_: IllegalArgumentException) { null }
        }, { id -> children(tree, id).documents }, ::checkWork)
    }

    private fun directoryName(tree: Uri, id: String): String {
        checkWork()
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val signal = CancellationSignal(); activeQuery = signal
        if (cancelled) signal.cancel()
        return try {
            resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null, signal)?.use { cursor ->
                checkWork()
                if (!cursor.moveToFirst()) throw java.io.FileNotFoundException("Music folder is unavailable")
                require(cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) { "Choose a music folder" }
                cursor.getString(0)?.take(180)?.takeIf { it.isNotBlank() } ?: "Music"
            } ?: throw java.io.FileNotFoundException("Music folder is unavailable")
        } finally { activeQuery = null }
    }

    private fun children(tree: Uri, id: String): Children {
        checkWork()
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val signal = CancellationSignal()
        val children = ArrayList<MusicFolderDocument>()
        var limited = false
        activeQuery = signal
        if (cancelled) signal.cancel()
        try {
            resolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null, signal)?.use { cursor ->
                while (cursor.moveToNext()) {
                    checkWork()
                    if (documentsSeen >= MAX_DOCUMENTS) { limited = true; break }
                    documentsSeen++
                    val child = MusicFolderDocument(cursor.getString(0) ?: continue,
                        cursor.getString(1) ?: continue, cursor.getString(2) ?: "")
                    if (MusicFolderPolicy.valid(child)) children.add(child)
                }
            } ?: throw java.io.FileNotFoundException("Music folder is unavailable")
        } finally { activeQuery = null }
        return Children(children, limited)
    }

    private fun index(tree: Uri, playlist: Uri?, startDocumentId: String? = null): FolderIndex {
        val rootId = selectDirectory(tree, startDocumentId)
        val rootName = directoryName(tree, rootId)
        val playlistId = if (playlist?.authority == tree.authority) runCatching { DocumentsContract.getDocumentId(playlist) }.getOrNull() else null
        val visited = HashSet<String>()
        val audio = ArrayList<AudioDocument>()
        val pending = ArrayDeque<Directory>()
        pending.add(Directory(rootId, "", 0))
        var limited = false; var playlistDirectory = ""
        while (pending.isNotEmpty()) {
            checkWork()
            val folder = pending.removeFirst()
            if (!visited.add(folder.id)) continue
            if (folder.depth > MAX_DEPTH) { limited = true; continue }
            val children = children(tree, folder.id)
            limited = limited || children.limited
            for (child in children.documents.sortedWith { a, b -> NaturalFileOrder.compare(a.name, b.name) }) {
                val relative = if (folder.path.isEmpty()) child.name else "${folder.path}/${child.name}"
                if (child.id == playlistId) playlistDirectory = folder.path
                if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (folder.depth < MAX_DEPTH) pending.add(Directory(child.id, relative, folder.depth + 1)) else limited = true
                } else if (AudioFilePolicy.accepts(child.name, child.mime)) {
                    audio.add(AudioDocument(DocumentsContract.buildDocumentUriUsingTree(tree, child.id).toString(), child.name, relative))
                }
            }
            if (documentsSeen >= MAX_DOCUMENTS) { if (pending.isNotEmpty()) limited = true; break }
        }
        return FolderIndex(rootName, audio, playlistDirectory, limited)
    }

    private fun displayName(uri: Uri): String? {
        val signal = CancellationSignal(); activeQuery = signal
        if (cancelled) signal.cancel()
        return try { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal)?.use {
            if (it.moveToFirst()) it.getString(0)?.take(512) else null
        } } finally { activeQuery = null }
    }

    private fun begin() { check(!cancelled); documentsSeen = 0; deadline = SystemClock.elapsedRealtime() + 30_000 }
    private fun checkWork() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException()
        if (SystemClock.elapsedRealtime() > deadline) throw java.io.IOException("Folder import timed out")
    }
    override fun close() { cancelled = true; activeQuery?.cancel(); runCatching { activeInput?.close() } }
    companion object { const val MAX_DOCUMENTS = 4096; const val MAX_DEPTH = 12 }
}
