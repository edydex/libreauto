// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import java.io.FileNotFoundException
import java.util.ArrayDeque

data class MusicDirectory(val id: String, val name: String)

data class MusicFolderListing(
    val name: String,
    val documentId: String,
    val folders: List<MusicDirectory>,
    val tracks: List<Track>,
    val truncated: Boolean = false
)

internal data class MusicFolderDocument(val id: String, val name: String, val mime: String)

/** Opaque provider IDs are never interpreted as filesystem paths or joined to a root ID. */
internal object MusicFolderPolicy {
    const val DIRECTORY_MIME = "vnd.android.document/directory"
    const val MAX_DOCUMENTS = 4096
    const val MAX_DEPTH = 12

    fun valid(document: MusicFolderDocument): Boolean = validId(document.id) &&
        document.name.length in 1..512 && document.name != "." && document.name != ".." &&
        document.name.none { it == '/' || it == '\\' || it < ' ' }

    private fun validId(id: String): Boolean = id.length in 1..4096 && id.none { it < ' ' }

    /**
     * Some SAF providers do not implement isChildDocument. In that case, only IDs reached
     * by enumerating the granted root are accepted. A failed/limited proof denies access.
     */
    fun selectDirectory(
        rootId: String,
        requestedId: String?,
        isChild: (String) -> Boolean?,
        children: (String) -> List<MusicFolderDocument>,
        checkWork: () -> Unit
    ): String {
        require(validId(rootId)) { "Invalid music folder" }
        val target = requestedId ?: return rootId
        require(validId(target)) { "Invalid music folder" }
        if (target == rootId) return rootId
        checkWork()
        when (isChild(target)) {
            true -> return target
            false -> throw SecurityException("This folder is outside the selected music folder")
            null -> Unit
        }
        val pending = ArrayDeque<Pair<String, Int>>()
        val visited = HashSet<String>()
        pending.add(rootId to 0)
        var seen = 0
        while (pending.isNotEmpty()) {
            checkWork()
            val (id, depth) = pending.removeFirst()
            if (!visited.add(id)) continue
            for (child in children(id)) {
                checkWork()
                if (++seen > MAX_DOCUMENTS) throw FileNotFoundException("Folder is too large to verify")
                if (!valid(child) || child.mime != DIRECTORY_MIME || depth >= MAX_DEPTH) continue
                if (child.id == target) return target
                pending.add(child.id to depth + 1)
            }
        }
        throw SecurityException("This folder is outside the selected music folder or is unavailable")
    }

    fun listing(name: String, id: String, children: List<MusicFolderDocument>, limited: Boolean,
                documentUri: (String) -> String): MusicFolderListing {
        val valid = children.asSequence().take(MAX_DOCUMENTS).filter(::valid).distinctBy { it.id }
            .sortedWith { a, b -> NaturalFileOrder.compare(a.name, b.name) }.toList()
        val directories = valid.filter { it.mime == DIRECTORY_MIME }
        val audio = valid.filter { it.mime != DIRECTORY_MIME && AudioFilePolicy.accepts(it.name, it.mime) }
        return MusicFolderListing(name.take(180), id,
            directories.take(PlayerQueue.MAX_SIZE).map { MusicDirectory(it.id, it.name) },
            audio.take(PlayerQueue.MAX_SIZE).map { Track(documentUri(it.id), it.name.take(180)) },
            limited || children.size > MAX_DOCUMENTS || directories.size > PlayerQueue.MAX_SIZE ||
                audio.size > PlayerQueue.MAX_SIZE)
    }
}
