// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.graphics.Point
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.TextUtils
import android.view.Gravity
import android.view.DragEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.bluetoothauto.model.Track
import org.bluetoothauto.playback.*
import org.bluetoothauto.integration.SharedLibraryBridge
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** A selected-file library. Saved playlists and the current listening queue are separate. */
class MusicLibraryActivity : Activity() {
    private var library = MusicLibrary()
    private var tab = "playlists"
    private var playlistId: String? = null
    private var folderId: String? = null
    private val path = mutableListOf<MusicDirectory>()
    private var listing: MusicFolderListing? = null
    private var folderFailure: String? = null
    private lateinit var page: LinearLayout
    private lateinit var list: ListView
    private val rows = ArrayList<MusicRow>()
    private var pendingTarget: String? = null
    private var pendingPlaylist: Uri? = null
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1))
    private var task: Future<*>? = null
    private var reader: MusicImporter? = null
    private var progress: AlertDialog? = null
    private var generation = 0
    private var screenEpoch = 0
    private var dragSession: ReorderDrag? = null
    private var dragOrigin: View? = null
    private var dropIndex = -1
    private var dragY = 0f
    private var edgeScroll = 0
    private val dragHandler = Handler(Looper.getMainLooper())
    private val scrollDrag = object : Runnable {
        override fun run() {
            if (dragSession == null || edgeScroll == 0 || !::list.isInitialized) return
            list.scrollListBy(edgeScroll)
            updateDropTarget(dragY)
            dragHandler.postDelayed(this, 40)
        }
    }
    private fun dp(n: Int) = PlayerUi.dp(this, n)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        tab = state?.getString("tab") ?: "playlists"
        playlistId = state?.getString("playlistId") ?: intent.getStringExtra("playlistId")
        folderId = state?.getString("folderId")
        pendingTarget = state?.getString("pendingTarget")
        pendingPlaylist = state?.getString("pendingPlaylist")?.let(Uri::parse)
        val ids = state?.getStringArrayList("pathIds").orEmpty()
        val names = state?.getStringArrayList("pathNames").orEmpty()
        ids.zip(names).forEach { path.add(MusicDirectory(it.first, it.second)) }
        render()
        work("Opening music library…", { MusicLibraryStore.load(this) }, cancellable = false) { loaded ->
            library = loaded
            val folder = selectedFolder()
            if (folder != null) browse(folder, path.lastOrNull()?.id, false) else render()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("tab", tab); out.putString("playlistId", playlistId); out.putString("folderId", folderId)
        out.putString("pendingTarget", pendingTarget); out.putString("pendingPlaylist", pendingPlaylist?.toString())
        out.putStringArrayList("pathIds", ArrayList(path.map { it.id }))
        out.putStringArrayList("pathNames", ArrayList(path.map { it.name }))
        super.onSaveInstanceState(out)
    }
    override fun onStop() { stopDrag(); super.onStop() }
    override fun onDestroy() { stopDrag(); cancelWork(); worker.shutdownNow(); super.onDestroy() }
    @Deprecated("Activity back compatibility")
    override fun onBackPressed() { back() }

    private fun back() {
        cancelWork()
        when {
            playlistId != null -> { playlistId = null; render() }
            folderId != null && path.size > 1 -> {
                val parentPath = path.dropLast(1)
                selectedFolder()?.let { browse(it, parentPath.last().id, false, parentPath) } ?: leaveFolder()
            }
            folderId != null -> leaveFolder()
            else -> finish()
        }
    }
    private fun leaveFolder() { folderId = null; listing = null; folderFailure = null; path.clear(); render() }
    private fun selectedPlaylist() = library.playlists.firstOrNull { it.id == playlistId }
    private fun selectedFolder() = library.folders.firstOrNull { it.id == folderId }

    private fun render(keepScroll: Boolean = false) {
        if (isFinishing || isDestroyed) return
        stopDrag(); screenEpoch++
        val previousPosition = if (keepScroll && ::list.isInitialized) list.firstVisiblePosition else 0
        val previousTop = if (keepScroll && ::list.isInitialized) list.getChildAt(0)?.top ?: 0 else 0
        rows.clear()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(PlayerUi.background)
            setPadding(dp(16), 0, dp(16), 0)
        }
        setContentView(root)
        val playlist = selectedPlaylist()
        val folder = selectedFolder()
        val title = playlist?.name ?: if (folder != null) path.lastOrNull()?.name ?: folder.name else "Music library"
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(PlayerUi.icon(this, "back", if (playlistId != null || folderId != null) "Back to library" else "Back to player") { back() }, LinearLayout.LayoutParams(dp(48), dp(58)))
        header.addView(PlayerUi.label(this, title, 22, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        if (playlist != null) header.addView(PlayerUi.icon(this, "more", "Playlist options: ${playlist.name}") { playlistMenu(playlist, header) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        if (playlist == null && folder == null) {
            val tabs = LinearLayout(this).apply { setPadding(0, dp(4), 0, dp(12)) }
            listOf("playlists" to "Playlists", "folders" to "Folders").forEach { (key, text) ->
                tabs.addView(PlayerUi.button(this, text, key == tab) { tab = key; render() }.apply {
                    contentDescription = text; isSelected = key == tab
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (key == "playlists") marginEnd = dp(8) })
            }
            root.addView(tabs)
        }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(10)) }
        when {
            playlist != null -> renderPlaylist(playlist)
            folder != null -> renderFolder(folder)
            tab == "folders" -> renderFolders()
            else -> renderPlaylists()
        }
        list = ListView(this).apply {
            divider = null; isVerticalScrollBarEnabled = false; clipToPadding = false
            setPadding(0, 0, 0, dp(20)); addHeaderView(page, null, false)
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(position: Int) = rows[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = bindRow(rows[position], convertView)
            }
        }
        list.setOnDragListener { _, event -> handleDrag(event) }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        if (keepScroll) list.post { list.setSelectionFromTop(previousPosition, previousTop) }
    }

    private fun renderPlaylists() {
        val actions = LinearLayout(this)
        actions.addView(PlayerUi.button(this, "+  New playlist", true) { nameDialog("New playlist", "") { name ->
            mutate("Creating playlist…", { it.createPlaylist(name) }) { updated ->
                playlistId = updated.playlists.lastOrNull()?.id; render()
            }
        } }.apply { contentDescription = "Create playlist" }, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(PlayerUi.icon(this, "import", "Import VLC playlist") { choosePlaylist(null) }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(8) })
        page.addView(actions)
        if (library.playlists.isEmpty()) emptyState("Music for every drive", "Create a playlist, or import an M3U, XSPF or PLS playlist from VLC.")
        else {
            note("${library.playlists.size} ${if (library.playlists.size == 1) "playlist" else "playlists"} · Hold ⋮ to reorder")
            library.playlists.forEachIndexed { index, playlist ->
                item(playlist.name, trackCount(playlist.tracks.size), "playlist", "Open playlist: ${playlist.name}",
                    { playlistId = playlist.id; render() }, "Playlist options: ${playlist.name}", { anchor -> playlistMenu(playlist, anchor) },
                    ReorderItem(null, index, playlist.id))
            }
        }
    }

    private fun renderPlaylist(playlist: MusicPlaylist) {
        val actions = LinearLayout(this)
        actions.addView(PlayerUi.button(this, "Play in order", true) { play(playlist.tracks, false, playlist.name) }.apply {
            contentDescription = "Play in order"; isEnabled = playlist.tracks.isNotEmpty()
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        actions.addView(PlayerUi.button(this, "Shuffle") { play(playlist.tracks, true, playlist.name) }.apply {
            contentDescription = "Shuffle playlist"; isEnabled = playlist.tracks.isNotEmpty()
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        page.addView(actions)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), dp(14), 0, dp(6)) }
        bar.addView(PlayerUi.label(this, "${trackCount(playlist.tracks.size)} · Hold ⋮ to reorder", 13, false, PlayerUi.muted), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(PlayerUi.button(this, "Add music") { addMusicMenu(playlist.id) }.apply { contentDescription = "Add music to playlist"; textSize = 14f }, LinearLayout.LayoutParams(-2, dp(46)))
        page.addView(bar)
        if (playlist.tracks.isEmpty()) emptyState("Add your first tracks", "Choose audio files, add a music folder or import a VLC playlist.")
        playlist.tracks.forEachIndexed { index, track ->
            item(track.title, track.artist.takeIf { it.isNotBlank() } ?: fileHint(track), "${index + 1}", "Play track: ${track.title}",
                { play(playlist.tracks, false, playlist.name, index) }, "Track options: ${track.title}", { anchor -> trackMenu(playlist, index, anchor) },
                ReorderItem(playlist.id, index, "${playlist.id}:$index"))
        }
    }

    private fun renderFolders() {
        page.addView(PlayerUi.button(this, "+  Add music folder", true) { chooseTree(ADD_FOLDER) }.apply {
            contentDescription = "Add music folder"
        }, LinearLayout.LayoutParams(-1, dp(50)))
        if (library.folders.isEmpty()) emptyState("Your folders, where you left them", "Choose a music folder once. Browse its albums and subfolders here whenever you need them.")
        else {
            note("Only the folders you choose are accessed.")
            library.folders.forEach { folder ->
                item(folder.name, "Browse music and subfolders", "folder", "Browse folder: ${folder.name}",
                    { openFolder(folder) }, "Folder options: ${folder.name}", { anchor -> folderMenu(folder, anchor) })
            }
        }
    }

    private fun renderFolder(folder: MusicFolder) {
        val current = listing
        if (current == null) {
            val failure = folderFailure
            if (failure == null) note("Opening folder…")
            else {
                emptyState("Folder unavailable", failure)
                page.addView(PlayerUi.button(this, "Try again", true) {
                    folderFailure = null; render(); browse(folder, path.lastOrNull()?.id, false)
                }.apply { contentDescription = "Retry opening folder" }, LinearLayout.LayoutParams(-1, dp(50)))
                page.addView(PlayerUi.button(this, "Back to library") { leaveFolder() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
            }
            return
        }
        if (path.size > 1) {
            val parent = path[path.lastIndex - 1]
            page.addView(PlayerUi.button(this, "‹  ${parent.name}") { back() }.apply { contentDescription = "Parent folder" }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })
        }
        val actions = LinearLayout(this)
        actions.addView(PlayerUi.button(this, "Play folder", true) { readFolder(folder, current.documentId) { playFolder(it, false, current.name) } }.apply { contentDescription = "Play folder in order" }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) })
        actions.addView(PlayerUi.button(this, "Shuffle") { readFolder(folder, current.documentId) { playFolder(it, true, current.name) } }.apply { contentDescription = "Shuffle folder" }, LinearLayout.LayoutParams(0, dp(50), 1f))
        page.addView(actions)
        note("Folder playback includes subfolders, in filename order.")
        page.addView(PlayerUi.button(this, "Add folder to playlist") { pickTarget { target ->
            readFolder(folder, current.documentId) { result -> appendTracks(target, result.tracks, result) }
        } }.apply { contentDescription = "Add folder to playlist" }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        current.folders.forEach { child ->
            item(child.name, "Folder", "folder", "Open subfolder: ${child.name}", { browse(folder, child.id, true) })
        }
        current.tracks.forEachIndexed { index, track ->
            item(track.title, track.artist.takeIf { it.isNotBlank() } ?: fileHint(track), "music", "Play track: ${track.title}",
                { play(current.tracks, false, current.name, index) }, "Track options: ${track.title}", { anchor ->
                    popup(anchor, listOf("Add to playlist" to { pickTarget { target -> appendTracks(target, listOf(track)) } }))
                })
        }
        if (current.folders.isEmpty() && current.tracks.isEmpty()) emptyState("No audio here", "Open another folder, or add a different music folder from the Folders tab.")
        if (current.truncated) note("This view is limited to 512 tracks and 4,096 items. Open a smaller subfolder for more.")
    }

    private fun playlistMenu(playlist: MusicPlaylist, anchor: View) {
        val index = library.playlists.indexOfFirst { it.id == playlist.id }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "Rename" to { nameDialog("Rename playlist", playlist.name) { name -> mutate("Renaming playlist…", { it.renamePlaylist(playlist.id, name) }) } }
        if (index > 0) options += "Move up" to { mutate("Reordering playlists…", { it.movePlaylist(playlist.id, index - 1) }, keepScroll = true) }
        if (index >= 0 && index < library.playlists.lastIndex) options += "Move down" to { mutate("Reordering playlists…", { it.movePlaylist(playlist.id, index + 1) }, keepScroll = true) }
        options += "Delete playlist" to {
            AlertDialog.Builder(this).setTitle("Delete ${playlist.name}?").setMessage("Your audio files and current playback stay as they are.")
                .setPositiveButton("Delete") { _, _ -> mutate("Deleting playlist…", { it.removePlaylist(playlist.id) }) { if (playlistId == playlist.id) playlistId = null; render() } }
                .setNegativeButton("Cancel", null).show()
        }
        popup(anchor, options)
    }

    private fun trackMenu(playlist: MusicPlaylist, index: Int, anchor: View) {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (index > 0) options += "Move up" to { mutate("Reordering tracks…", { it.moveTrack(playlist.id, index, index - 1) }, keepScroll = true) }
        if (index < playlist.tracks.lastIndex) options += "Move down" to { mutate("Reordering tracks…", { it.moveTrack(playlist.id, index, index + 1) }, keepScroll = true) }
        options += "Move to top" to { mutate("Reordering tracks…", { it.moveTrack(playlist.id, index, 0) }, keepScroll = true) }
        options += "Move to bottom" to { mutate("Reordering tracks…", { it.moveTrack(playlist.id, index, playlist.tracks.lastIndex) }, keepScroll = true) }
        options += "Add to another playlist" to { pickTarget(exclude = playlist.id) { target -> appendTracks(target, listOf(playlist.tracks[index])) } }
        options += "Remove from playlist" to { mutate("Removing track…", { it.removeTrack(playlist.id, index) }, keepScroll = true) }
        popup(anchor, options)
    }

    private fun folderMenu(folder: MusicFolder, anchor: View) {
        popup(anchor, listOf(
            "Rename" to { nameDialog("Rename folder", folder.name) { name -> mutate("Renaming folder…", { it.renameFolder(folder.id, name) }) } },
            "Remove from library" to {
                AlertDialog.Builder(this).setTitle("Remove ${folder.name}?").setMessage("Your files, playlists and current playback stay as they are.")
                    .setPositiveButton("Remove") { _, _ -> mutate("Removing folder…", { it.removeFolder(folder.id) }) }
                    .setNegativeButton("Cancel", null).show()
            }
        ))
    }

    private fun addMusicMenu(target: String) {
        AlertDialog.Builder(this).setTitle("Add music").setItems(arrayOf("Choose audio files", "From a saved folder", "Choose a new folder", "Import a VLC playlist")) { _, choice ->
            when (choice) {
                0 -> chooseFiles(target)
                1 -> pickFolder { folder -> readFolder(folder, null) { appendTracks(target, it.tracks, it) } }
                2 -> { pendingTarget = target; chooseTree(ADD_FOLDER_TO_PLAYLIST) }
                3 -> choosePlaylist(target)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun pickFolder(chosen: (MusicFolder) -> Unit) {
        if (library.folders.isEmpty()) { toast("Add a music folder from the Folders tab first."); return }
        AlertDialog.Builder(this).setTitle("Choose music folder").setItems(library.folders.map { it.name }.toTypedArray()) { _, index -> chosen(library.folders[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickTarget(exclude: String? = null, chosen: (String) -> Unit) {
        val playlists = library.playlists.filter { it.id != exclude }
        val names = playlists.map { it.name } + "+  New playlist"
        AlertDialog.Builder(this).setTitle("Add to playlist").setItems(names.toTypedArray()) { _, index ->
            if (index < playlists.size) chosen(playlists[index].id)
            else nameDialog("New playlist", "") { name ->
                mutate("Creating playlist…", { it.createPlaylist(name) }) { chosen(it.playlists.last().id) }
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseFiles(target: String) {
        pendingTarget = target
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, FILES)
    }
    private fun chooseTree(request: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), request)
    }
    private fun choosePlaylist(target: String?) {
        pendingTarget = target
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, PLAYLIST)
    }
    private fun linkPlaylist() {
        val choices = library.folders.map { it.name } + "Choose another music folder…"
        AlertDialog.Builder(this).setTitle("Where is the playlist’s music?")
            .setItems(choices.toTypedArray()) { _, index ->
                if (index < library.folders.size) importPlaylist(Uri.parse(library.folders[index].treeUri), false)
                else chooseTree(PLAYLIST_FOLDER)
            }.setNegativeButton("Cancel") { _, _ -> pendingPlaylist = null; pendingTarget = null }.show()
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        when (request) {
            FILES -> {
                val target = pendingTarget ?: return
                pendingTarget = null
                val uris = mutableListOf<Uri>()
                data?.clipData?.let { clip -> for (i in 0 until minOf(clip.itemCount, 513)) uris.add(clip.getItemAt(i).uri) }
                data?.data?.let(uris::add)
                work("Reading audio files…", { input ->
                    val imported = input.files(uris)
                    val retained = imported.tracks.filter { retain(Uri.parse(it.uri), remember = false) }
                    rememberGrants(retained.map { it.uri })
                    imported.copy(tracks = retained, skipped = imported.skipped + imported.tracks.size - retained.size)
                }) { imported -> appendTracks(target, imported.tracks, imported) }
            }
            ADD_FOLDER, ADD_FOLDER_TO_PLAYLIST -> {
                val tree = data?.data ?: return
                if (!retain(tree)) { showError("Android could not keep access to this folder. Please choose it again."); return }
                val target = if (request == ADD_FOLDER_TO_PLAYLIST) pendingTarget else null
                pendingTarget = null
                work("Opening music folder…", { it.browse(tree) }) { result ->
                    mutate("Saving music folder…", { state ->
                        if (state.folders.any { it.treeUri == tree.toString() }) state
                        else state.addFolder(result.name.take(MusicLibrary.MAX_NAME_LENGTH), tree.toString())
                    }) { updated ->
                        val folder = updated.folders.first { it.treeUri == tree.toString() }
                        if (target != null) readFolder(folder, null) { appendTracks(target, it.tracks, it) }
                        else {
                            tab = "folders"; folderId = folder.id; playlistId = null; listing = result
                            path.clear(); path.add(MusicDirectory(result.documentId, result.name)); render()
                        }
                    }
                }
            }
            PLAYLIST -> { pendingPlaylist = data?.data; if (pendingPlaylist != null) linkPlaylist() }
            PLAYLIST_FOLDER -> data?.data?.let { importPlaylist(it, true) }
        }
    }

    private fun importPlaylist(tree: Uri, newlySelected: Boolean) {
        val document = pendingPlaylist ?: return
        val target = pendingTarget
        pendingPlaylist = null; pendingTarget = null
        if (newlySelected && !retain(tree)) { showError("Android could not keep access to the music folder. Please choose it again."); return }
        work("Reading VLC playlist…", { reader ->
            val imported = reader.playlist(document, tree)
            if (imported.tracks.isEmpty()) throw IllegalArgumentException("No matching local audio was found. Choose the folder containing the playlist’s music. M3U, XSPF and PLS playlists are supported.")
            rememberGrants(listOf(tree.toString()))
            imported
        }) { imported ->
            mutate("Saving playlist…", { state ->
                if (target != null) state.addTracks(target, imported.tracks)
                else state.createPlaylist(imported.sourceName.substringBeforeLast('.', imported.sourceName).take(MusicLibrary.MAX_NAME_LENGTH), imported.tracks)
            }) { updated ->
                playlistId = target ?: updated.playlists.last().id; folderId = null; render()
                importNotice(imported)
            }
        }
    }

    private fun openFolder(folder: MusicFolder) {
        playlistId = null; folderId = folder.id; listing = null; folderFailure = null; path.clear()
        render(); browse(folder, null, true)
    }
    private fun browse(folder: MusicFolder, documentId: String?, push: Boolean, replacementPath: List<MusicDirectory>? = null) {
        work("Opening folder…", { it.browse(Uri.parse(folder.treeUri), documentId) }, onError = { error ->
            val message = readableError(error)
            if (listing == null) { folderFailure = message; render() } else showError(message)
        }, onCancelled = { if (listing == null) leaveFolder() }) { result ->
            folderId = folder.id; playlistId = null; listing = result; folderFailure = null
            if (replacementPath != null) { path.clear(); path.addAll(replacementPath) }
            if (push || path.isEmpty()) path.add(MusicDirectory(result.documentId, result.name))
            render()
        }
    }
    private fun readFolder(folder: MusicFolder, documentId: String?, done: (MusicImportResult) -> Unit) {
        work("Reading music folder…", { it.folder(Uri.parse(folder.treeUri), documentId) }) { result ->
            if (result.tracks.isEmpty()) showError("No supported audio was found in this folder or its subfolders.") else done(result)
        }
    }
    private fun appendTracks(target: String, tracks: List<Track>, imported: MusicImportResult? = null) {
        if (tracks.isEmpty()) { showError("No readable audio files were added. Please choose the files or folder again."); return }
        mutate("Adding music…", { it.addTracks(target, tracks) }) {
            playlistId = target; folderId = null; listing = null; render()
            if (imported != null) importNotice(imported) else toast("Added ${trackCount(tracks.size)}")
        }
    }
    private fun importNotice(imported: MusicImportResult) {
        val detail = buildList {
            if (imported.skipped > 0) add("${imported.skipped} unavailable ${if (imported.skipped == 1) "entry was" else "entries were"} skipped")
            if (imported.truncated) add("the read limit was reached; use a smaller folder or playlist for the rest")
        }.joinToString("; ")
        if (detail.isNotEmpty()) AlertDialog.Builder(this).setTitle("Added ${trackCount(imported.tracks.size)}").setMessage(detail.replaceFirstChar { it.uppercase() }).setPositiveButton("OK", null).show()
        else toast("Added ${trackCount(imported.tracks.size)}")
    }

    private fun playFolder(result: MusicImportResult, shuffled: Boolean, name: String) {
        if (!result.truncated) { play(result.tracks, shuffled, name); return }
        AlertDialog.Builder(this).setTitle("This folder is larger than the read limit")
            .setMessage("${trackCount(result.tracks.size).replaceFirstChar { it.uppercase() }} were found before the 512-track or 4,096-item limit. Play these tracks now, or open a smaller subfolder to choose more precisely.")
            .setPositiveButton(if (shuffled) "Shuffle these tracks" else "Play these tracks") { _, _ -> play(result.tracks, shuffled, name) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun play(tracks: List<Track>, shuffled: Boolean, name: String, index: Int = 0) {
        if (tracks.isEmpty()) return
        work("Starting music…", {
            Library.save(this, tracks); Library.shuffle(this, shuffled)
            true
        }, cancellable = false) {
            if (SharedLibraryBridge.playOnProjectionIfActive(this, tracks, shuffled, index)) {
                toast(if (shuffled) "Shuffling $name" else "Playing $name")
                finish()
                return@work
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 70)
            BluetoothAutoService.command(this, BluetoothAutoService.PLAY_INDEX, if (shuffled) Random.nextInt(tracks.size) else index)
            toast(if (shuffled) "Shuffling $name" else "Playing $name")
            finish()
        }
    }

    private fun retain(uri: Uri, remember: Boolean = true): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (remember) rememberGrants(listOf(uri.toString()))
        true
    } catch (_: Exception) { false }
    private fun rememberGrants(uris: List<String>) {
        Library.musicGrants(this, (Library.musicGrants(this) + uris).distinct())
    }
    private fun mutate(message: String, change: (MusicLibrary) -> MusicLibrary, keepScroll: Boolean = false, after: ((MusicLibrary) -> Unit)? = null) {
        work(message, { MusicLibraryStore.edit(this, change) }, cancellable = false) { updated ->
            library = updated
            if (after != null) after(updated) else render(keepScroll)
        }
    }
    private fun <T> work(message: String, action: (MusicImporter) -> T, cancellable: Boolean = true,
                         onError: ((Throwable) -> Unit)? = null, onCancelled: (() -> Unit)? = null, done: (T) -> Unit) {
        cancelWork()
        val token = ++generation
        val input = MusicImporter(this); reader = input
        val builder = AlertDialog.Builder(this).setMessage(message)
        if (cancellable) builder.setNegativeButton("Cancel") { _, _ -> cancelWork(); onCancelled?.invoke() }
        progress = builder.create().apply {
            setCancelable(cancellable); setCanceledOnTouchOutside(false)
            if (cancellable) setOnCancelListener { cancelWork(); onCancelled?.invoke() }
            show()
        }
        task = worker.submit {
            val result = runCatching { action(input) }; input.close()
            runOnUiThread {
                if (token != generation || isFinishing || isDestroyed) return@runOnUiThread
                progress?.dismiss(); progress = null; task = null; reader = null
                result.fold(done) { error ->
                    if (onError != null) onError(error) else showError(readableError(error))
                }
            }
        }
    }
    private fun cancelWork() {
        generation++; reader?.close(); reader = null; task?.cancel(true); task = null
        worker.purge(); progress?.dismiss(); progress = null
    }

    private fun nameDialog(title: String, current: String, done: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(current); setSingleLine(); hint = if (title.contains("folder", true)) "Folder name" else "Playlist name"; selectAll()
            filters = arrayOf(InputFilter.LengthFilter(80)); setTextColor(PlayerUi.ink)
            contentDescription = if (title.contains("folder", true)) "Folder name" else "Playlist name"
        }
        val holder = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field, FrameLayout.LayoutParams(-1, -2)) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(holder).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            field.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = field.text.toString().trim()
                if (name.isBlank()) field.error = "Enter a name" else { dialog.dismiss(); done(name) }
            }
        }
        dialog.show()
    }
    private fun popup(anchor: View, options: List<Pair<String, () -> Unit>>) {
        val menu = PopupMenu(this, anchor)
        options.forEachIndexed { index, pair -> menu.menu.add(0, index, index, pair.first) }
        menu.setOnMenuItemClickListener { item -> options[item.itemId].second(); true }; menu.show()
    }
    private data class ReorderItem(val ownerPlaylist: String?, val index: Int, val key: String)
    private data class ReorderDrag(val item: ReorderItem, val epoch: Int, val playlistIds: List<String>, val tracks: List<Track>?)
    private data class MusicRow(val title: String, val subtitle: String, val icon: String, val description: String,
                                val click: () -> Unit, val optionsDescription: String?, val options: ((View) -> Unit)?, val reorder: ReorderItem?)
    private class RowViews(val card: LinearLayout, val content: LinearLayout, val number: TextView, val icon: ImageView,
                           val title: TextView, val subtitle: TextView, val options: ImageButton, val chevron: ImageView,
                           var reorder: ReorderItem? = null)
    private fun item(title: String, subtitle: String, icon: String, description: String, click: () -> Unit,
                     optionsDescription: String? = null, options: ((View) -> Unit)? = null, reorder: ReorderItem? = null) {
        rows.add(MusicRow(title, subtitle, icon, description, click, optionsDescription, options, reorder))
    }
    private fun bindRow(data: MusicRow, recycled: View?): View {
        val outer: FrameLayout
        val views: RowViews
        if (recycled?.tag is RowViews) {
            outer = recycled as FrameLayout; views = outer.tag as RowViews
        } else {
            outer = FrameLayout(this).apply { setPadding(0, dp(6), 0, dp(2)) }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = PlayerUi.shape(this@MusicLibraryActivity, PlayerUi.card, 15)
                setPadding(dp(8), dp(9), dp(4), dp(9)); minimumHeight = dp(72)
            }
            val content = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; isClickable = true }
            val number = PlayerUi.label(this, "", 13, false, PlayerUi.muted).apply { gravity = Gravity.CENTER }
            val icon = ImageView(this).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            content.addView(number, LinearLayout.LayoutParams(dp(36), dp(44)))
            content.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(5), 0, dp(4), 0) }
            val title = PlayerUi.label(this, "", 16, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            val subtitle = PlayerUi.label(this, "", 12, false, PlayerUi.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
            labels.addView(title); labels.addView(subtitle)
            content.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(content, LinearLayout.LayoutParams(0, -2, 1f))
            val options = PlayerUi.icon(this, "more", "More options") { }
            row.addView(options, LinearLayout.LayoutParams(dp(48), dp(48)))
            val chevron = ImageView(this).apply { setImageDrawable(PlayerIcon("chevron", PlayerUi.muted)); setPadding(dp(12), dp(12), dp(12), dp(12)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            row.addView(chevron, LinearLayout.LayoutParams(dp(40), dp(44)))
            outer.addView(row, FrameLayout.LayoutParams(-1, -2))
            views = RowViews(row, content, number, icon, title, subtitle, options, chevron); outer.tag = views
        }
        views.content.contentDescription = data.description; views.content.setOnClickListener { data.click() }
        val numbered = data.icon.toIntOrNull() != null
        views.number.visibility = if (numbered) View.VISIBLE else View.GONE; views.number.text = data.icon
        views.icon.visibility = if (numbered) View.GONE else View.VISIBLE
        if (!numbered) views.icon.setImageDrawable(PlayerIcon(data.icon, PlayerUi.accent))
        views.title.text = data.title; views.subtitle.text = data.subtitle; views.subtitle.visibility = if (data.subtitle.isBlank()) View.GONE else View.VISIBLE
        views.options.visibility = if (data.options == null) View.GONE else View.VISIBLE
        views.chevron.visibility = if (data.options == null) View.VISIBLE else View.GONE
        views.options.contentDescription = data.optionsDescription ?: "More options"
        views.options.setOnClickListener { data.options?.invoke(views.options) }
        views.reorder = data.reorder
        views.options.tooltipText = if (data.reorder != null) "Tap for options · Hold and drag to reorder" else "More options"
        views.options.setOnLongClickListener(if (data.reorder != null) View.OnLongClickListener {
            if (rows.size > 1) startReorder(data.reorder, outer, views.options)
            else { data.options?.invoke(views.options); true }
        } else null)
        views.options.accessibilityDelegate = if (data.reorder != null) object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "Hold and drag to reorder"))
            }
        } else null
        styleDragRow(outer, views)
        views.content.setOnLongClickListener(if (data.options != null) View.OnLongClickListener { data.options.invoke(views.options); true } else null)
        return outer
    }
    private fun startReorder(item: ReorderItem, row: View, handle: View): Boolean {
        stopDrag()
        val session = ReorderDrag(item, screenEpoch, library.playlists.map { it.id },
            item.ownerPlaylist?.let { id -> library.requirePlaylist(id).tracks.toList() })
        dragSession = session; dragOrigin = handle; dropIndex = item.index
        val shadow = object : View.DragShadowBuilder(row) {
            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                size.set(row.width, row.height)
                touch.set((row.width - dp(28)).coerceIn(0, row.width), row.height / 2)
            }
        }
        val started = handle.startDragAndDrop(ClipData.newPlainText("Reorder music", ""), shadow, session, 0)
        if (!started) { stopDrag(false); return false }
        handle.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        refreshDragRows()
        return true
    }
    private fun handleDrag(event: DragEvent): Boolean {
        val session = event.localState as? ReorderDrag ?: return false
        if (event.action == DragEvent.ACTION_DRAG_ENDED) {
            if (dragSession === session) stopDrag(false)
            return true
        }
        if (dragSession !== session || session.epoch != screenEpoch) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                dragY = event.y; updateDropTarget(dragY)
                val edge = dp(76)
                val nextScroll = when {
                    event.y < edge -> -dp(14)
                    event.y > list.height - edge -> dp(14)
                    else -> 0
                }
                // Motion arrives faster than the scroll tick. Keep an existing edge loop
                // running so a moving/jittering finger cannot continually postpone it.
                if (nextScroll != edgeScroll) {
                    edgeScroll = nextScroll
                    dragHandler.removeCallbacks(scrollDrag)
                    if (edgeScroll != 0) dragHandler.postDelayed(scrollDrag, 40)
                }
            }
            DragEvent.ACTION_DRAG_EXITED -> { edgeScroll = 0; dragHandler.removeCallbacks(scrollDrag); dropIndex = -1; refreshDragRows() }
            DragEvent.ACTION_DROP -> {
                updateDropTarget(event.y)
                val target = dropIndex
                val source = session.item
                stopDrag(false)
                if (target !in rows.indices || source.index == target) return true
                mutate("Saving order…", { state ->
                    if (source.ownerPlaylist == null) {
                        if (state.playlists.map { it.id } != session.playlistIds || state.playlists.getOrNull(source.index)?.id != source.key)
                            throw MusicLibraryException("The playlists changed during the move. Please try again.")
                        state.movePlaylist(source.key, target)
                    } else {
                        if (state.requirePlaylist(source.ownerPlaylist).tracks != session.tracks)
                            throw MusicLibraryException("This playlist changed during the move. Please try again.")
                        state.moveTrack(source.ownerPlaylist, source.index, target)
                    }
                }, keepScroll = true)
            }
        }
        return true
    }
    private fun updateDropTarget(y: Float) {
        if (dragSession == null || rows.isEmpty()) return
        val position = list.pointToPosition(dp(20), y.toInt()).let { hit ->
            if (hit != AdapterView.INVALID_POSITION) hit
            else if (y < list.height / 2f) list.firstVisiblePosition else list.lastVisiblePosition
        }
        val target = MusicReorderPolicy.targetIndex(position, list.headerViewsCount, rows.size) ?: return
        if (target != dropIndex) { dropIndex = target; refreshDragRows() }
    }
    private fun styleDragRow(outer: View, views: RowViews) {
        val session = dragSession
        val index = views.reorder?.index
        val source = session != null && views.reorder == session.item
        val target = session != null && index == dropIndex && !source
        outer.alpha = if (source) .38f else 1f
        views.card.elevation = if (source) dp(6).toFloat() else 0f
        views.card.background = PlayerUi.shape(this, if (target) 0xFF244A50.toInt() else PlayerUi.card, 15).apply {
            if (target) setStroke(dp(2), PlayerUi.accent)
        }
    }
    private fun refreshDragRows() {
        if (!::list.isInitialized) return
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            val holder = child.tag as? RowViews ?: continue
            styleDragRow(child, holder)
        }
    }
    private fun stopDrag(cancelSystem: Boolean = true) {
        val origin = dragOrigin
        dragSession = null; dragOrigin = null; dropIndex = -1; edgeScroll = 0
        dragHandler.removeCallbacks(scrollDrag)
        if (cancelSystem) origin?.cancelDragAndDrop()
        refreshDragRows()
    }
    private fun emptyState(title: String, text: String) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(42), dp(20), dp(28)) }
        body.addView(PlayerUi.label(this, title, 21, true).apply { gravity = Gravity.CENTER })
        body.addView(PlayerUi.label(this, text, 15, false, PlayerUi.muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        page.addView(body, LinearLayout.LayoutParams(-1, -2))
    }
    private fun note(text: String) { page.addView(PlayerUi.label(this, text, 13, false, PlayerUi.muted).apply { setPadding(dp(3), dp(14), dp(3), dp(10)) }) }
    private fun fileHint(track: Track) = Uri.decode(track.uri).substringAfterLast('/').substringAfterLast(':')
        .takeIf { it != track.title && it.isNotBlank() && it.length < 90 } ?: "Audio file"
    private fun trackCount(count: Int) = "$count ${if (count == 1) "track" else "tracks"}"
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    private fun readableError(error: Throwable): String = when (error) {
        is SecurityException -> "Access to this folder or file is no longer available. Choose it again from the Android picker."
        is java.io.FileNotFoundException -> "This folder or file could not be found. It may have moved or been removed."
        else -> error.message?.take(350)?.takeIf { it.isNotBlank() } ?: "This folder or audio file is unavailable. Choose it again to restore access."
    }
    private fun showError(text: String) { AlertDialog.Builder(this).setTitle("Music library").setMessage(text).setPositiveButton("OK", null).show() }
    companion object {
        private const val FILES = 61
        private const val ADD_FOLDER = 62
        private const val PLAYLIST = 63
        private const val PLAYLIST_FOLDER = 64
        private const val ADD_FOLDER_TO_PLAYLIST = 65
    }
}
