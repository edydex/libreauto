package org.openandroidauto.media

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.openandroidauto.integration.SharedLibraryIntegration

/** Uses Android's file picker: no broad storage permission, media scan or private-library upload. */
class AudioLibraryActivity : Activity() {
    private var pickerLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharedLibraryIntegration.delegate?.let {
            it.openMusicLibrary(this)
            finish()
            return
        }
        pickerLaunched = savedInstanceState?.getBoolean("picker-launched") ?: false
        if (!pickerLaunched) {
            pickerLaunched = true
            try {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, PICK_AUDIO)
            } catch (_: Exception) {
                Toast.makeText(this, "No audio file picker is available", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("picker-launched", pickerLaunched)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Activity result callback is retained for a small platform-only picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_AUDIO) return
        if (resultCode == RESULT_OK && data != null) {
            val selected = mutableListOf<Uri>()
            data.clipData?.let { clip ->
                for (index in 0 until minOf(clip.itemCount, 512)) selected += clip.getItemAt(index).uri
            }
            data.data?.let { if (it !in selected) selected += it }
            val usable = selected.filter { it.scheme == "content" }.distinct().take(512)
            if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                usable.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: SecurityException) {
                        // Some providers only support a session grant; display a normal playback error if it expires.
                    }
                }
            }
            if (usable.isNotEmpty()) {
                MediaLibraryStore.replace(this, usable)
                sendBroadcast(Intent(MediaLibraryStore.ACTION_CHANGED).setPackage(packageName))
                Toast.makeText(this, "${usable.size} audio file${if (usable.size == 1) "" else "s"} selected", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    private companion object { const val PICK_AUDIO = 402 }
}
