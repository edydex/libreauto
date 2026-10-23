// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.playback

import org.bluetoothauto.model.Track
import java.util.UUID

/** Saved sources are independent of the playing queue. Editing a list does not interrupt playback. */
data class MusicPlaylist(val id: String, val name: String, val tracks: List<Track> = emptyList())
data class MusicFolder(val id: String, val name: String, val treeUri: String)

class MusicLibraryException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

data class MusicLibrary(
    val playlists: List<MusicPlaylist> = emptyList(),
    val folders: List<MusicFolder> = emptyList()
) {
    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), id: String = newId()): MusicLibrary =
        checked(copy(playlists = playlists + MusicPlaylist(id, cleanName(name), tracks.toList())))

    fun renamePlaylist(id: String, name: String): MusicLibrary = changePlaylist(id) { it.copy(name = cleanName(name)) }
    fun removePlaylist(id: String): MusicLibrary {
        requirePlaylist(id)
        return checked(copy(playlists = playlists.filterNot { it.id == id }))
    }
    fun movePlaylist(id: String, toIndex: Int): MusicLibrary {
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) throw MusicLibraryException("That playlist no longer exists.")
        return checked(copy(playlists = playlists.moved(index, toIndex)))
    }
    fun addTracks(id: String, tracks: List<Track>): MusicLibrary = changePlaylist(id) { it.copy(tracks = it.tracks + tracks) }
    fun replacePlaylistTracks(id: String, tracks: List<Track>): MusicLibrary = changePlaylist(id) { it.copy(tracks = tracks.toList()) }
    fun removeTrack(id: String, index: Int): MusicLibrary = changePlaylist(id) { playlist ->
        if (index !in playlist.tracks.indices) throw MusicLibraryException("That track no longer exists in this playlist.")
        playlist.copy(tracks = playlist.tracks.filterIndexed { i, _ -> i != index })
    }
    fun moveTrack(id: String, fromIndex: Int, toIndex: Int): MusicLibrary = changePlaylist(id) {
        it.copy(tracks = it.tracks.moved(fromIndex, toIndex))
    }
    fun addFolder(name: String, treeUri: String, id: String = newId()): MusicLibrary {
        if (folders.any { it.treeUri == treeUri }) throw MusicLibraryException("This folder is already in your library.")
        return checked(copy(folders = folders + MusicFolder(id, cleanName(name), treeUri)))
    }
    fun renameFolder(id: String, name: String): MusicLibrary {
        if (folders.none { it.id == id }) throw MusicLibraryException("That folder no longer exists in your library.")
        return checked(copy(folders = folders.map { if (it.id == id) it.copy(name = cleanName(name)) else it }))
    }
    fun removeFolder(id: String): MusicLibrary {
        if (folders.none { it.id == id }) throw MusicLibraryException("That folder no longer exists in your library.")
        return checked(copy(folders = folders.filterNot { it.id == id }))
    }

    fun requirePlaylist(id: String): MusicPlaylist = playlists.find { it.id == id }
        ?: throw MusicLibraryException("That playlist no longer exists.")

    /** Reject an invalid edit as a whole; never truncate a saved playlist or silently discard entries. */
    fun validate(): MusicLibrary {
        if (playlists.size > MAX_PLAYLISTS) throw MusicLibraryException("Your library can hold up to $MAX_PLAYLISTS playlists.")
        if (folders.size > MAX_FOLDERS) throw MusicLibraryException("Your library can hold up to $MAX_FOLDERS music folders.")
        if (playlists.sumOf { it.tracks.size.toLong() } > MAX_TOTAL_TRACKS) {
            throw MusicLibraryException("Your saved playlists can hold up to $MAX_TOTAL_TRACKS tracks in total.")
        }
        val ids = HashSet<String>()
        playlists.forEach { playlist ->
            validateId(playlist.id, ids)
            validateName(playlist.name)
            if (playlist.tracks.size > MAX_TRACKS) throw MusicLibraryException("A playlist can hold up to $MAX_TRACKS tracks.")
            playlist.tracks.forEach(::validateTrack)
        }
        val roots = HashSet<String>()
        folders.forEach { folder ->
            validateId(folder.id, ids)
            validateName(folder.name)
            if (!folder.treeUri.startsWith("content://") || !folder.treeUri.contains("/tree/") || folder.treeUri.length > MAX_URI_LENGTH) {
                throw MusicLibraryException("Choose a music folder using the Android folder picker.")
            }
            if (!roots.add(folder.treeUri)) throw MusicLibraryException("A music folder appears more than once in your library.")
        }
        return this
    }

    private fun changePlaylist(id: String, change: (MusicPlaylist) -> MusicPlaylist): MusicLibrary {
        requirePlaylist(id)
        return checked(copy(playlists = playlists.map { if (it.id == id) change(it) else it }))
    }

    companion object {
        const val MAX_PLAYLISTS = 64
        const val MAX_FOLDERS = 32
        const val MAX_TRACKS = 512
        const val MAX_TOTAL_TRACKS = 8192
        const val MAX_NAME_LENGTH = 100
        const val MAX_URI_LENGTH = 8192
        const val MAX_TRACK_LABEL_LENGTH = 2048
        fun newId(): String = UUID.randomUUID().toString()

        private fun cleanName(name: String): String = name.trim().also(::validateName)
        private fun validateName(name: String) {
            if (name.isBlank()) throw MusicLibraryException("Give this playlist or folder a name.")
            if (name.length > MAX_NAME_LENGTH) throw MusicLibraryException("Keep names to $MAX_NAME_LENGTH characters or fewer.")
            if (name.any { it == '\u0000' || it == '\r' || it == '\n' }) throw MusicLibraryException("Keep the name on one line.")
        }
        private fun validateId(id: String, seen: MutableSet<String>) {
            if (id.isBlank() || id.length > 128 || !seen.add(id)) throw MusicLibraryException("The saved library has an invalid or repeated item ID.")
        }
        private fun validateTrack(track: Track) {
            if ((!track.uri.startsWith("content://") && !track.uri.startsWith("file://")) ||
                track.uri.length > MAX_URI_LENGTH || track.uri == "content://" || track.uri == "file://" || track.uri.contains('\u0000')) {
                throw MusicLibraryException("Only local audio files can be saved in a playlist.")
            }
            if (track.title.length > MAX_TRACK_LABEL_LENGTH || track.artist.length > MAX_TRACK_LABEL_LENGTH) {
                throw MusicLibraryException("An audio file's title or artist is too long to save.")
            }
        }
        private fun checked(library: MusicLibrary): MusicLibrary = library.validate()
        private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> {
            if (fromIndex !in indices || toIndex !in indices) throw MusicLibraryException("That item cannot be moved to this position.")
            return toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
}
