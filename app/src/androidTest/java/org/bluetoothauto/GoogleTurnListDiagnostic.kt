// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Looper
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Explicit opt-in private UI diagnostic. Reads Maps' currently exposed tree without any UI action. */
object GoogleTurnListDiagnostic {
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val MAX_NODES = 2048
    private const val MAX_DEPTH = 48
    private const val MAX_CHILDREN = 256
    private const val MAX_WINDOWS = 32
    private const val MAX_OUTPUT_BYTES = 4_000_000L

    /** Returns only a content-free count. Text stays in the target app's private cache file. */
    fun run(test: Instrumentation): Int {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Run the diagnostic on the instrumentation thread" }
        val automation = test.uiAutomation
        val info = automation.serviceInfo
        val oldFlags = info.flags
        val output = File(test.targetContext.cacheDir, "google-turn-list.xml")
        val temporary = File(test.targetContext.cacheDir, "google-turn-list.xml.tmp")
        var count = 0
        var truncated = false
        var remainingText = 160_000
        try {
            // These flags affect only this instrumentation's automation service, never Maps/settings.
            info.flags = oldFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            automation.serviceInfo = info
            temporary.outputStream().use { stream ->
                val xml = Xml.newSerializer()
                xml.setOutput(LimitedOutput(stream), "UTF-8")
                xml.startDocument("UTF-8", true)
                xml.startTag(null, "maps-ui")

                fun attribute(name: String, value: CharSequence?, maximum: Int = 1000) {
                    val text = xmlText(value, minOf(maximum, remainingText))
                    remainingText -= text.length
                    xml.attribute(null, name, text)
                }

                fun node(value: AccessibilityNodeInfo, depth: Int, index: Int) {
                    if (count >= MAX_NODES || depth >= MAX_DEPTH) { truncated = true; return }
                    if (value.packageName?.toString() != MAPS_PACKAGE) return
                    count++
                    xml.startTag(null, "node")
                    xml.attribute(null, "index", index.toString())
                    attribute("text", value.text)
                    attribute("content-desc", value.contentDescription)
                    attribute("resource-id", value.viewIdResourceName, 300)
                    attribute("class", value.className, 180)
                    val bounds = Rect().also(value::getBoundsInScreen)
                    xml.attribute(null, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                    xml.attribute(null, "clickable", value.isClickable.toString())
                    xml.attribute(null, "scrollable", value.isScrollable.toString())
                    xml.attribute(null, "selected", value.isSelected.toString())
                    xml.attribute(null, "checkable", value.isCheckable.toString())
                    xml.attribute(null, "checked", value.isChecked.toString())
                    xml.attribute(null, "enabled", value.isEnabled.toString())
                    value.collectionItemInfo?.let { item ->
                        xml.attribute(null, "row-index", item.rowIndex.toString())
                        xml.attribute(null, "row-span", item.rowSpan.toString())
                        xml.attribute(null, "column-index", item.columnIndex.toString())
                        xml.attribute(null, "column-span", item.columnSpan.toString())
                    }
                    val children = value.childCount
                    if (children > MAX_CHILDREN) truncated = true
                    for (childIndex in 0 until minOf(children, MAX_CHILDREN)) {
                        if (count >= MAX_NODES) { truncated = true; break }
                        val child = value.getChild(childIndex) ?: continue
                        try { node(child, depth + 1, childIndex) }
                        finally { @Suppress("DEPRECATION") child.recycle() }
                    }
                    xml.endTag(null, "node")
                }

                // Deliberately no waitForIdle(), rootInActiveWindow polling, scrolling or clicks.
                val windows = automation.windows
                try {
                    if (windows.size > MAX_WINDOWS) truncated = true
                    windows.take(MAX_WINDOWS).forEach { window ->
                        val root = window.root ?: return@forEach
                        try {
                            if (root.packageName?.toString() == MAPS_PACKAGE) {
                                xml.startTag(null, "window")
                                xml.attribute(null, "id", window.id.toString())
                                xml.attribute(null, "type", window.type.toString())
                                xml.attribute(null, "active", window.isActive.toString())
                                node(root, 0, 0)
                                xml.endTag(null, "window")
                            }
                        } finally { @Suppress("DEPRECATION") root.recycle() }
                    }
                } finally {
                    windows.forEach { @Suppress("DEPRECATION") it.recycle() }
                }
                xml.startTag(null, "summary")
                xml.attribute(null, "node-count", count.toString())
                xml.attribute(null, "truncated", (truncated || remainingText == 0).toString())
                xml.attribute(null, "ui-actions", "0")
                xml.endTag(null, "summary")
                xml.endTag(null, "maps-ui")
                xml.endDocument()
                xml.flush()
                stream.fd.sync()
            }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            return count
        } finally {
            temporary.delete()
            info.flags = oldFlags
            automation.serviceInfo = info
        }
    }

    /** Keep XML1.0 legal and bounded even when an app exposes styled or malformed text. */
    private fun xmlText(value: CharSequence?, maximum: Int): String {
        if (value == null || maximum <= 0) return ""
        val text = value.toString()
        val output = StringBuilder(minOf(maximum, text.length))
        var index = 0
        while (index < text.length && output.length < maximum) {
            val point = Character.codePointAt(text, index)
            index += Character.charCount(point)
            if (point == 9 || point == 10 || point == 13 || point in 0x20..0xd7ff ||
                point in 0xe000..0xfffd || point in 0x10000..0x10ffff) {
                if (output.length + Character.charCount(point) > maximum) break
                output.appendCodePoint(point)
            }
        }
        return output.toString()
    }

    private class LimitedOutput(output: OutputStream) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) {
            if (count >= MAX_OUTPUT_BYTES) throw IOException("Private UI diagnostic exceeded its bound")
            out.write(value); count++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length.toLong() > MAX_OUTPUT_BYTES - count) throw IOException("Private UI diagnostic exceeded its bound")
            out.write(bytes, offset, length); count += length
        }
    }
}
