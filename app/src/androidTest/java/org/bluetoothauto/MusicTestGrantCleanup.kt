// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import org.bluetoothauto.playback.MusicImporter
import org.json.JSONObject

/** Revokes only persisted grants belonging to a positively identified disposable music fixture. */
object MusicTestGrantCleanup {
    private data class Grant(val uri: String, val read: Boolean, val write: Boolean) {
        val flags: Int get() = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    }

    fun run(test: Instrumentation, treeUri: String): String {
        val target = test.targetContext
        val resolver = target.contentResolver
        val tree = Uri.parse(treeUri)
        require(tree.scheme == "content" && !tree.authority.isNullOrBlank() && DocumentsContract.isTreeUri(tree)) {
            "An exact persisted disposable test-tree URI is required"
        }
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        require(rootId.isNotBlank()) { "The disposable test root has no document ID" }
        fun snapshot(): List<Grant> = resolver.persistedUriPermissions.map {
            Grant(it.uri.toString(), it.isReadPermission, it.isWritePermission)
        }.sortedWith(compareBy<Grant> { it.uri }.thenBy { it.flags })
        val before = snapshot()
        check(before.any { it.uri == treeUri && it.read }) { "The exact test tree must still have its persisted read grant" }

        // Identification is read-only and completes before any permission is changed.
        val root = MusicImporter(target).use { it.browse(tree) }
        check(root.documentId == rootId && root.name.startsWith("BluetoothAuto Music Test")) {
            "Refusing to revoke access to a folder that is not the disposable music fixture"
        }
        fun belongsToFixture(grant: Grant): Boolean {
            if (grant.uri == treeUri) return true
            val uri = Uri.parse(grant.uri)
            if (uri.scheme != "content" || uri.authority != tree.authority) return false
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
            return documentId == rootId || documentId.startsWith("$rootId/")
        }
        val owned = before.filter(::belongsToFixture)
        val protected = before.filterNot(::belongsToFixture)
        check(owned.isNotEmpty() && owned.all { it.flags != 0 }) { "No revocable disposable test grants were found" }

        // Individual file grants are independent of the containing tree grant. Release the tree last.
        owned.sortedBy { it.uri == treeUri }.forEach { grant ->
            resolver.releasePersistableUriPermission(Uri.parse(grant.uri), grant.flags)
        }
        val after = snapshot()
        check(after.filter(::belongsToFixture).isEmpty()) { "A disposable fixture grant remained after cleanup" }
        check(after.filterNot(::belongsToFixture) == protected) {
            "An unrelated persisted grant changed during disposable-fixture cleanup"
        }
        return JSONObject().put("released_count", owned.size).put("remaining_count", after.size)
            .put("remaining_test_grants", 0).put("other_grants_unchanged", true)
            .put("files_and_preferences_untouched", true).toString()
    }
}
