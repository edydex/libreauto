// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.net.Uri
import android.provider.DocumentsContract
import org.bluetoothauto.playback.MusicImporter
import org.json.JSONArray
import org.json.JSONObject

/** Reads only an explicitly granted, disposable SAF tree; never edits files, library or playback. */
object MusicFolderBrowserAcceptance {
    fun run(test: Instrumentation, treeUri: String): String {
        val target = test.targetContext
        val resolver = target.contentResolver
        val tree = Uri.parse(treeUri)
        require(tree.scheme == "content" && DocumentsContract.isTreeUri(tree)) { "A granted test tree is required" }
        check(resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }) {
            "The disposable music test folder must have a persisted read grant"
        }
        val root = MusicImporter(target).use { it.browse(tree) }
        check(root.name.startsWith("BluetoothAuto Music Test")) { "Refusing a non-test music folder" }
        check(root.documentId == DocumentsContract.getTreeDocumentId(tree))
        check(!root.truncated)
        check(root.tracks.map { it.title } == listOf("Track 2.m4a", "Track 10.m4a")) {
            "Root media must be in natural numeric order, with no subfolder tracks or playlist files"
        }
        check(root.folders.map { it.name } == listOf("Album")) { "Folders must be listed separately" }
        val album = root.folders.single()
        val child = MusicImporter(target).use { it.browse(tree, album.id) }
        check(child.name == "Album" && child.documentId == album.id)
        check(child.folders.isEmpty() && child.tracks.map { it.title } == listOf("Track 1.m4a"))
        val recursive = MusicImporter(target).use { it.folder(tree) }
        check(!recursive.truncated && recursive.tracks.size == 3)
        check(recursive.tracks.map { it.uri }.toSet() == (root.tracks + child.tracks).map { it.uri }.toSet()) {
            "Playing the root folder must include its nested album"
        }
        val selected = MusicImporter(target).use { it.folder(tree, album.id) }
        check(selected.sourceName == "Album" && selected.tracks == child.tracks) {
            "Playing a selected subfolder must not include its parent's music"
        }
        for (track in recursive.tracks) {
            check(Uri.parse(track.uri).authority == tree.authority)
            resolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")?.use { descriptor ->
                check(descriptor.length != 0L) { "Fixture audio is empty" }
            } ?: error("Granted audio cannot be reopened")
        }
        val outside = "bluetoothauto-unrelated-${System.nanoTime()}"
        check(runCatching { MusicImporter(target).use { it.browse(tree, outside) } }.isFailure) {
            "An ID not enumerated from the selected tree was accepted"
        }
        check(runCatching { MusicImporter(target).use { it.folder(tree, outside) } }.isFailure) {
            "Recursive playback accepted an ID outside the selected tree"
        }

        // Find the optional local playlist through the granted provider's own enumeration.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root.documentId)
        var playlist: Uri? = null
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                check(++count <= 32) { "Unexpectedly large disposable fixture" }
                if (cursor.getString(1) == "Test.m3u") playlist =
                    DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
            }
        } ?: error("Cannot enumerate disposable fixture")
        val playlistChecked = playlist?.let { document ->
            val imported = MusicImporter(target).use { it.playlist(document, tree) }
            check(imported.playlist && !imported.truncated && imported.skipped == 0)
            check(imported.tracks.map { it.uri } == listOf(root.tracks[1].uri,
                child.tracks.single().uri, root.tracks[1].uri)) {
                "M3U import must preserve order, nested paths and duplicate entries"
            }
            true
        } ?: false
        return JSONObject().put("root_tracks", JSONArray(root.tracks.map { it.title }))
            .put("folder_count", root.folders.size).put("subfolder_tracks", child.tracks.size)
            .put("recursive_tracks", recursive.tracks.size).put("subfolder_only", true)
            .put("outside_ids_rejected", true).put("audio_reopened", true)
            .put("playlist_order_duplicates_checked", playlistChecked)
            .put("files_and_library_unchanged", true).toString()
    }
}
