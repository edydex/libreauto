// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Private, bounded prepared road areas. All disk/JSON operations must run on a worker thread. */
class TurnRoadStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "prepared-roads"))

    data class Area(val center: GeoPoint, val radius: Double, val savedAt: Long, val source: String,
                    val graph: TurnMatcher.Graph)
    data class Summary(val areaCount: Int, val totalBytes: Long, val newestDownloadedAtMillis: Long)
    data class Diagnostics(val catalogRefreshes: Long, val graphReads: Long)
    private data class Stamp(val name: String, val bytes: Long, val modified: Long)
    private data class Metadata(val file: File, val center: GeoPoint, val radius: Double,
                                val savedAt: Long, val source: String, val bytes: Long)
    private data class Catalog(val inventory: List<Stamp>, val entries: List<Metadata>)
    private var catalog: Catalog? = null
    @Volatile private var catalogRefreshes = 0L
    @Volatile private var graphReads = 0L
    @Volatile var areaCount = 0
        private set
    fun diagnostics() = Diagnostics(catalogRefreshes, graphReads)

    /** Imports normal user-approved OSM data; no graph/location is bundled with the app. */
    @JvmOverloads fun save(center: GeoPoint, radius: Double, graphJson: String, source: String = OVERPASS): Area = synchronized(DISK_LOCK) {
        writeArea(center, radius, graphJson, source, clock())
    }

    /** User file import. The original data age is preserved, never refreshed by importing it. */
    fun importArea(envelopeJson: String): Area = synchronized(DISK_LOCK) {
        require(envelopeJson.length <= MAX_IMPORT_BYTES && envelopeJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_IMPORT_BYTES)
        val raw = JSONObject(envelopeJson)
        require(raw.getString("format") == FORMAT && raw.getInt("version") == 1)
        require(raw.opt("radiusMeters") is Number && raw.opt("downloadedAtMillis") is Number)
        val center = raw.getJSONObject("center")
        writeArea(GeoPoint(center.getDouble("latitude"), center.getDouble("longitude")), raw.getDouble("radiusMeters"),
            raw.getJSONObject("graph").toString(), USER_APPROVED_OSM, timestamp(raw, "downloadedAtMillis"))
    }

    fun exportArea(area: Area): String = synchronized(DISK_LOCK) {
        checkInterrupted()
        val elements = org.json.JSONArray()
        area.graph.nodes.forEach { (id, point) -> elements.put(JSONObject().put("type", "node").put("id", id).put("lat", point.latitude).put("lon", point.longitude)) }
        area.graph.ways.forEach { way -> elements.put(JSONObject().put("type", "way").put("id", way.id)
            .put("nodes", org.json.JSONArray(way.nodes)).put("tags", JSONObject(way.tags))) }
        val result = JSONObject().put("format", FORMAT).put("version", 1)
            .put("center", JSONObject().put("latitude", area.center.latitude).put("longitude", area.center.longitude))
            .put("radiusMeters", area.radius).put("downloadedAtMillis", area.savedAt)
            .put("graph", JSONObject().put("elements", elements)).toString()
        require(result.toByteArray(StandardCharsets.UTF_8).size <= MAX_IMPORT_BYTES)
        result
    }

    fun summary(): Summary = synchronized(DISK_LOCK) {
        ensureCatalog(clock())
        val entries = catalog?.entries.orEmpty()
        areaCount = entries.size
        Summary(entries.size, entries.sumOf { it.bytes }, entries.maxOfOrNull { it.savedAt } ?: 0L)
    }

    /** Removes only this store's prepared graph files, preserving unrelated private app state. */
    fun clear(): Int = synchronized(DISK_LOCK) {
        checkInterrupted()
        var count = 0
        directory.listFiles()?.filter { it.name.matches(FILE_NAME) || it.name.matches(TEMP_NAME) }?.forEach {
            if (Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.delete()) count++
        }
        catalog = null
        areaCount = 0
        count
    }

    private fun writeArea(center: GeoPoint, radius: Double, graphJson: String, source: String, savedAt: Long): Area {
        checkInterrupted()
        require(radius.isFinite() && radius in MIN_RADIUS..MAX_RADIUS && source in SOURCES)
        val now = clock()
        require(validTime(now, savedAt))
        val graph = TurnRoadCodec.parseGraph(graphJson)
        require(graph.nodes.isNotEmpty() && graph.ways.isNotEmpty())
        require(intersectsArea(center, radius, graph))
        val envelope = JSONObject().put("version", 1).put("latitude", center.latitude).put("longitude", center.longitude)
            .put("radius", radius).put("savedAt", savedAt).put("source", source).put("graph", JSONObject(graphJson))
        val bytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_AREA_BYTES)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Prepared road storage unavailable")
        val id = UUID.randomUUID().toString()
        val temporary = File(directory, "$id.tmp")
        val target = File(directory, "$id.json")
        try {
            FileOutputStream(temporary).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkInterrupted()
                    val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count); offset += count
                }
                output.fd.sync()
            }
            checkInterrupted()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            catalog = null
            trim(now, target)
        } finally { temporary.delete() }
        return Area(center, radius, savedAt, source, graph)
    }

    /** Full circular coverage is required; partially overlapping or expired areas are a miss. */
    fun load(center: GeoPoint, requiredRadius: Double): Area? = synchronized(DISK_LOCK) {
        require(requiredRadius.isFinite() && requiredRadius > 0 && requiredRadius <= MAX_RADIUS)
        val now = clock()
        val fresh = ensureCatalog(now)
        val candidates = catalog?.entries.orEmpty().filter { it.center.distanceTo(center) + requiredRadius <= it.radius }
            .sortedByDescending { it.savedAt }
        var result: Area? = null
        for (entry in candidates) {
            checkInterrupted()
            try {
                result = fresh?.firstOrNull { it.first == entry.file }?.second ?: readArea(entry.file, now)
                break
            } catch (interrupted: InterruptedException) { throw interrupted }
            catch (_: Exception) { entry.file.delete(); catalog = null; areaCount = (areaCount - 1).coerceAtLeast(0) }
        }
        result
    }

    /** Only metadata survives the call; graphs from the first validation pass are returned locally. */
    private fun ensureCatalog(now: Long): List<Pair<File, Area>>? {
        checkInterrupted()
        val currentInventory = inventory()
        val previous = catalog
        if (previous != null && previous.inventory == currentInventory && previous.entries.all { validTime(now, it.savedAt) }) return null
        val valid = readValid(now)
        catalog = Catalog(inventory(), valid.map { (file, area) ->
            Metadata(file, area.center, area.radius, area.savedAt, area.source, file.length())
        })
        areaCount = valid.size
        catalogRefreshes++
        return valid
    }

    private fun inventory(): List<Stamp> = directory.listFiles().orEmpty().asSequence()
        .filter { it.name.matches(FILE_NAME) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        .map { Stamp(it.name, it.length(), it.lastModified()) }.sortedBy { it.name }.toList()

    private fun readValid(now: Long, preserve: File? = null): List<Pair<File, Area>> {
        checkInterrupted()
        val children = directory.listFiles().orEmpty()
        // A process killed between writing and atomic rename may leave an unpublished file.
        children.filter { it.name.matches(TEMP_NAME) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.forEach { it.delete() }
        val files = children.filter { it.name.matches(FILE_NAME) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .sortedWith(compareByDescending<File> { it == preserve }.thenByDescending { it.lastModified() })
        val result = ArrayList<Pair<File, Area>>()
        var totalBytes = 0L
        for ((index, file) in files.withIndex()) {
            checkInterrupted()
            val fileBytes = file.length()
            if (index >= MAX_AREAS || fileBytes !in 1..MAX_AREA_BYTES.toLong() || totalBytes + fileBytes > MAX_TOTAL_BYTES) { file.delete(); continue }
            try {
                result.add(file to readArea(file, now))
                totalBytes += fileBytes
            } catch (interrupted: InterruptedException) { throw interrupted }
            catch (_: Exception) { file.delete() }
        }
        return result
    }

    private fun readArea(file: File, now: Long): Area {
        require(file.length() in 1..MAX_AREA_BYTES.toLong())
        val bytes = Files.newInputStream(file.toPath()).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_AREA_BYTES) throw IOException("Prepared area too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val raw = JSONObject(TurnRoadCodec.decode(bytes))
        require(raw.getInt("version") == 1 && raw.opt("savedAt") is Number && raw.opt("radius") is Number)
        val savedAt = timestamp(raw, "savedAt")
        require(validTime(now, savedAt))
        val radius = raw.getDouble("radius")
        val source = raw.getString("source")
        require(radius.isFinite() && radius in MIN_RADIUS..MAX_RADIUS && source in SOURCES)
        val center = GeoPoint(raw.getDouble("latitude"), raw.getDouble("longitude"))
        graphReads++
        val graph = TurnRoadCodec.parseGraph(raw.getJSONObject("graph").toString())
        require(graph.nodes.isNotEmpty() && graph.ways.isNotEmpty())
        require(intersectsArea(center, radius, graph))
        return Area(center, radius, savedAt, source, graph)
    }

    private fun trim(now: Long, preserve: File) {
        val entries = readValid(now, preserve).sortedByDescending { if (it.first == preserve) Long.MAX_VALUE else it.second.savedAt }
        var bytes = 0L; var count = 0
        for ((file, _) in entries) {
            val size = file.length()
            if (count >= MAX_AREAS || bytes + size > MAX_TOTAL_BYTES) file.delete()
            else { bytes += size; count++ }
        }
        areaCount = count
    }

    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun timestamp(raw: JSONObject, key: String): Long {
        val value = raw.get(key)
        require(value is Number)
        val result = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == result.toDouble())
        return result
    }
    private fun validTime(now: Long, savedAt: Long): Boolean = now > 0 && savedAt > 0 &&
        if (savedAt > now) savedAt - now <= FUTURE_TOLERANCE_MILLIS else now - savedAt <= MAX_AGE_MILLIS

    /** Reject an unrelated payload; completeness of a user's asserted download area is not inferable. */
    private fun intersectsArea(center: GeoPoint, radius: Double, graph: TurnMatcher.Graph): Boolean {
        val latitudeSpan = radius / 111_195.08
        val longitudeSpan = latitudeSpan / kotlin.math.cos(Math.toRadians(center.latitude)).coerceAtLeast(.01)
        val points = graph.nodes.values
        return points.minOf { it.latitude } <= center.latitude + latitudeSpan && points.maxOf { it.latitude } >= center.latitude - latitudeSpan &&
            points.minOf { it.longitude } <= center.longitude + longitudeSpan && points.maxOf { it.longitude } >= center.longitude - longitudeSpan
    }
    companion object {
        const val FORMAT = "bluetoothauto-road-area"
        const val OVERPASS = "OVERPASS"
        const val USER_APPROVED_OSM = "USER_APPROVED_OSM"
        const val MAX_AREAS = 8
        const val MAX_TOTAL_BYTES = 16_000_000L
        const val MAX_AREA_BYTES = TurnRoadCodec.MAX_BYTES + 8192
        const val MAX_IMPORT_BYTES = MAX_AREA_BYTES
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        const val FUTURE_TOLERANCE_MILLIS = 5L * 60 * 1000
        const val MIN_RADIUS = 100.0
        const val MAX_RADIUS = 5000.0
        private val SOURCES = setOf(OVERPASS, USER_APPROVED_OSM)
        private val FILE_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")
        private val TEMP_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp")
        private val DISK_LOCK = Any()
    }
}
