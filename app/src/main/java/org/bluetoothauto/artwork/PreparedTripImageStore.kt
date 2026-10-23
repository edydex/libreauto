// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A trip namespace is mandatory: matching a road alone never authorizes a stale trip picture. */
internal data class PreparedTripImageKey(val trip: String, val turn: PreparedTurnKey) {
    init { require(trip.isNotBlank() && trip.length <= 256) }
    val tripHash: String get() = digest(trip.toByteArray(Charsets.UTF_8))
    val imageHash: String get() = digest(ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            for (value in listOf(trip, turn.geometry, turn.units, turn.styleRevision)) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                out.writeInt(encoded.size); out.write(encoded)
            }
        }
    }.toByteArray())

    companion object {
        internal fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/**
 * Worker-thread-only, bounded private PNG storage. Names contain digests, never route text/coordinates.
 * Stored bytes are validated before allocation; native bitmap decoding is the renderer's responsibility.
 */
internal class PreparedTripImageStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maximumEntries: Int = MAX_ENTRIES,
    private val maximumBytes: Long = MAX_TOTAL_BYTES
) {
    data class SaveResult(val saved: Boolean, val evictedImageHashes: Set<String> = emptySet())
    private var activeTrip: String? = null
    init { require(maximumEntries in 1..MAX_ENTRIES); require(maximumBytes in 1..MAX_TOTAL_BYTES) }

    /** Selecting a new captured route invalidates and removes the previous route's images. */
    fun activate(trip: String) = synchronized(DISK_LOCK) {
        checkInterrupted()
        val hash = PreparedTripImageKey(trip, PreparedTurnKey("namespace", "", "")).tripHash
        activeTrip = hash
        entries().filter { !it.name.startsWith("$hash-") }.forEach { it.delete() }
        trim()
    }

    /** A read miss is harmless; incomplete/corrupt/expired files are removed. */
    fun load(key: PreparedTripImageKey): ByteArray? = synchronized(DISK_LOCK) {
        checkInterrupted()
        if (key.tripHash != activeTrip) return null
        val file = file(key)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            require(file.length() in HEADER_BYTES.toLong()..(HEADER_BYTES + MAX_IMAGE_BYTES).toLong())
            val all = Files.newInputStream(file.toPath()).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    checkInterrupted()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(out.size() + count <= HEADER_BYTES + MAX_IMAGE_BYTES)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            DataInputStream(ByteArrayInputStream(all)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC))
                val writtenAt = input.readLong()
                require(validTime(writtenAt))
                val identity = ByteArray(32).also(input::readFully)
                require(identity.contentEquals(hexBytes(key.imageHash)))
                val checksum = ByteArray(32).also(input::readFully)
                val count = input.readInt()
                require(count in 24..MAX_IMAGE_BYTES && input.available() == count)
                val image = ByteArray(count).also(input::readFully)
                require(checksum.contentEquals(MessageDigest.getInstance("SHA-256").digest(image)))
                require(validImageHeader(image))
                file.setLastModified(clock()) // Access order controls bounded disk eviction.
                image
            }
        } catch (interrupted: InterruptedException) { throw interrupted }
        catch (_: Exception) { file.delete(); null }
    }

    fun save(key: PreparedTripImageKey, png: ByteArray): Boolean = saveResult(key, png).saved

    fun saveResult(key: PreparedTripImageKey, png: ByteArray): SaveResult = synchronized(DISK_LOCK) {
        checkInterrupted()
        if (key.tripHash != activeTrip) return SaveResult(false)
        require(png.size in 24..MAX_IMAGE_BYTES && validImageHeader(png))
        if (png.size + HEADER_BYTES > maximumBytes) return SaveResult(false)
        if (!directory.isDirectory && !directory.mkdirs()) return SaveResult(false)
        val temporary = File(directory, "${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { raw ->
                val out = DataOutputStream(raw)
                out.write(MAGIC); out.writeLong(clock()); out.write(hexBytes(key.imageHash))
                out.write(MessageDigest.getInstance("SHA-256").digest(png)); out.writeInt(png.size)
                var offset = 0
                while (offset < png.size) {
                    checkInterrupted()
                    val count = minOf(8192, png.size - offset)
                    out.write(png, offset, count); offset += count
                }
                out.flush(); raw.fd.sync()
            }
            checkInterrupted()
            Files.move(temporary.toPath(), file(key).toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
            file(key).setLastModified(clock())
            val evicted = trim()
            return SaveResult(file(key).isFile, evicted)
        } finally { temporary.delete() }
    }

    fun clear() = synchronized(DISK_LOCK) {
        checkInterrupted()
        entries().forEach { it.delete() }
        temporaryFiles().forEach { it.delete() }
    }

    private fun file(key: PreparedTripImageKey) = File(directory, "${key.tripHash}-${key.imageHash}.map")
    private fun entries(): List<File> = directory.listFiles().orEmpty().filter {
        it.name.matches(FILE_NAME) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
    private fun temporaryFiles(): List<File> = directory.listFiles().orEmpty().filter {
        it.name.matches(TEMP_NAME) && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS)
    }
    private fun trim(): Set<String> {
        checkInterrupted()
        temporaryFiles().forEach { it.delete() }
        var count = 0; var bytes = 0L
        val evicted = hashSetOf<String>()
        for (file in entries().sortedByDescending { it.lastModified() }) {
            checkInterrupted()
            val size = file.length()
            if (size !in HEADER_BYTES.toLong()..(HEADER_BYTES + MAX_IMAGE_BYTES).toLong() ||
                count >= maximumEntries || bytes + size > maximumBytes) {
                if (file.delete()) evicted.add(file.name.substringAfter('-').removeSuffix(".map"))
            }
            else { count++; bytes += size }
        }
        return evicted
    }
    private fun validTime(at: Long): Boolean {
        val now = clock()
        return at > 0 && now > 0 && if (at > now) at - now <= FUTURE_TOLERANCE_MS else now - at <= MAX_AGE_MS
    }
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        const val MAX_ENTRIES = 256
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val FUTURE_TOLERANCE_MS = 5L * 60 * 1000
        private val MAGIC = "BTAMAP01".toByteArray(Charsets.US_ASCII)
        private val DISK_LOCK = Any()
        private const val HEADER_BYTES = 8 + 8 + 32 + 32 + 4
        private val FILE_NAME = Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.map")
        private val TEMP_NAME = Regex("[a-f0-9-]{36}\\.tmp")
        private val PNG = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        internal fun validImageHeader(bytes: ByteArray): Boolean = bytes.size >= 24 &&
            PNG.indices.all { bytes[it] == PNG[it] } &&
            bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII)) &&
            java.nio.ByteBuffer.wrap(bytes, 8, 4).int == 13 &&
            java.nio.ByteBuffer.wrap(bytes, 16, 4).int == 512 &&
            java.nio.ByteBuffer.wrap(bytes, 20, 4).int == 512
    }
}
