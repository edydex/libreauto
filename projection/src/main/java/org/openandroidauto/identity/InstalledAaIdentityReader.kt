// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.identity

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.InputStream
import java.io.FileDescriptor
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded preparation only. No callback, socket, report, key serialization or foreign code.
 * This function's source/parse frame has fully exited before a session begins. */
internal object InstalledAaIdentityReader {
    private val busy = AtomicBoolean()
    internal data class Snapshot(val version: Long, val name: String?, val uid: Int,
        val source: String, val splits: List<String>, val signers: List<String>)
    internal data class Prepared(val material: InstalledAaIdentityMaterial.Material,
        val snapshot: Snapshot, val file: StructStat)
    internal data class Result(val status: IdentityStatus, val prepared: Prepared? = null)
    private class Refused(val status: IdentityStatus) : Exception()

    fun prepare(context: Context, stop: ProjectionStopSignal): Result {
        if (Build.VERSION.SDK_INT < 32) return Result(IdentityStatus.CHECK_FAILED)
        if (Looper.myLooper() == Looper.getMainLooper()) return Result(IdentityStatus.WRONG_THREAD)
        if (!busy.compareAndSet(false, true)) return Result(IdentityStatus.BUSY)
        val app = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + InstalledAaIdentityPolicy.TIMEOUT_MS
        var retained: InstalledAaIdentityMaterial.Material? = null
        fun check() {
            if (stop.isStopped || Thread.currentThread().isInterrupted)
                throw InstalledAaIdentityMaterial.Abort(InstalledAaIdentityMaterial.AbortReason.CANCELLED)
            if (SystemClock.elapsedRealtime() >= deadline)
                throw InstalledAaIdentityMaterial.Abort(InstalledAaIdentityMaterial.AbortReason.TIMED_OUT)
        }
        try {
            if (Process.myUid() != app.applicationInfo.uid) throw Refused(IdentityStatus.CHECK_FAILED)
            check()
            val before = snapshot(app)
            if (!InstalledAaIdentityPolicy.versionMatches(before.version, before.name))
                throw Refused(IdentityStatus.UNSUPPORTED_VERSION)
            if (!InstalledAaIdentityPolicy.signerMatches(before.signers.toTypedArray()))
                throw Refused(IdentityStatus.SIGNER_MISMATCH)
            if (before.splits.size > InstalledAaIdentityPolicy.MAX_SPLITS) throw Refused(IdentityStatus.INPUT_BOUNDS)
            val source = File(before.source).canonicalFile
            // Allocate the owner BEFORE opening: no FileInputStream construction window
            // can lose the fd, and close has only this one owner (never a retry on reused fd).
            val input = ApkSource()
            // No long-session body is invoked within this use{} or the Java prepare frame.
            val prepared = input.use {
                try { input.open(source.path) }
                catch (_: Exception) { throw Refused(IdentityStatus.APK_NOT_READABLE) }
                val descriptor = input.descriptor()
                val initial = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(initial.st_mode) || !InstalledAaIdentityPolicy.apkSizeAllowed(initial.st_size))
                    throw Refused(IdentityStatus.INPUT_BOUNDS)
                check()
                val material = InstalledAaIdentityMaterial.prepare(input, initial.st_size, ::check)
                retained = material
                check()
                if (!sameFile(initial, Os.fstat(descriptor)) || !sameFile(initial, Os.stat(source.path)) || before != snapshot(app))
                    throw Refused(IdentityStatus.PACKAGE_CHANGED)
                check()
                Prepared(material, before, initial)
            } // APK descriptor is actually closed before returning a prepared lease.
            val success = Result(IdentityStatus.READY, prepared)
            retained = null
            return success
        } catch (aborted: InstalledAaIdentityMaterial.Abort) {
            return Result(if (aborted.reason == InstalledAaIdentityMaterial.AbortReason.CANCELLED)
                IdentityStatus.CANCELLED else IdentityStatus.TIMED_OUT)
        } catch (refused: Refused) { return Result(refused.status) }
        catch (refused: InstalledAaIdentityMaterial.Refused) { return Result(IdentityStatus.valueOf(refused.status.name)) }
        catch (_: SecurityException) { return Result(IdentityStatus.APK_NOT_READABLE) }
        catch (_: Exception) { return Result(IdentityStatus.CHECK_FAILED) }
        finally { retained?.close(); busy.set(false) }
    }

    /** Fixed metadata recheck immediately before first TLS use. No APK is reread. */
    fun stillCurrent(context: Context, prepared: Prepared): Boolean = try {
        prepared.snapshot == snapshot(context.applicationContext) &&
            sameFile(prepared.file, Os.stat(prepared.snapshot.source))
    } catch (_: Exception) { false }

    fun check(context: Context, stop: ProjectionStopSignal): IdentityStatus {
        val result = prepare(context, stop)
        result.prepared?.material?.close()
        return result.status
    }

    @Suppress("DEPRECATION")
    private fun snapshot(context: Context): Snapshot {
        if (Build.VERSION.SDK_INT < 32) throw Refused(IdentityStatus.CHECK_FAILED)
        val info = try { context.packageManager.getPackageInfo(InstalledAaIdentityPolicy.PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES) }
            catch (_: PackageManager.NameNotFoundException) { throw Refused(IdentityStatus.PACKAGE_UNAVAILABLE) }
        val app = info.applicationInfo ?: throw Refused(IdentityStatus.PACKAGE_UNAVAILABLE)
        val signers = info.signingInfo?.apkContentsSigners ?: throw Refused(IdentityStatus.SIGNER_MISMATCH)
        if (signers.size !in 1..8) throw Refused(IdentityStatus.SIGNER_MISMATCH)
        return Snapshot(info.longVersionCode, info.versionName, app.uid,
            app.sourceDir ?: throw Refused(IdentityStatus.APK_NOT_READABLE), app.splitSourceDirs?.toList().orEmpty(),
            signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) } }.sorted())
    }
    private class ApkSource : InputStream() {
        private var owned: FileDescriptor? = null
        private val single = ByteArray(1)
        fun open(path: String) {
            if (Build.VERSION.SDK_INT < 32) throw IOException("Projection requires Android 12L or newer")
            check(owned == null)
            // Returned resource is immediately published into a preallocated owner.
            owned = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        }
        fun descriptor(): FileDescriptor = checkNotNull(owned)
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            val count = try { Os.read(descriptor(), bytes, offset, length) }
                catch (_: android.system.ErrnoException) { throw IOException("APK read failed") }
            return if (count == 0) -1 else count
        }
        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        override fun close() {
            val descriptor = owned ?: return
            owned = null // Never retry close after ambiguous kernel/provider failure.
            single.fill(0)
            Os.close(descriptor)
        }
    }
    private fun sameFile(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime && OsConstants.S_ISREG(b.st_mode)
}
