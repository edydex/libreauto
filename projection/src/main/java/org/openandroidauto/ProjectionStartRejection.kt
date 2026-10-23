package org.openandroidauto

/** A rejected service instance cannot later acquire a session because a queued start raced a mode change. */
internal class ProjectionStartRejection {
    var rejected: Boolean = false
        private set
    private var creationAcknowledged = false
    private var firstStartSeen = false
    private var lastAcknowledgedStart: Int? = null
    fun shouldReject(modeAllowed: Boolean): Boolean = rejected || !modeAllowed

    /** onCreate and its first onStart share one foreground request. Later distinct start IDs
     * can represent new foreground requests and must be acknowledged again. Failed promotion
     * is never recorded as acknowledgement.
     */
    fun rejectStart(startId: Int?, acknowledgeForeground: () -> Unit, removeForeground: () -> Unit, stopService: () -> Unit): Boolean {
        val acknowledge = when {
            startId == null -> !creationAcknowledged
            !firstStartSeen -> {
                firstStartSeen = true
                if (creationAcknowledged) { lastAcknowledgedStart = startId; false } else true
            }
            else -> lastAcknowledgedStart != startId
        }
        return reject({
            if (acknowledge) {
                acknowledgeForeground()
                if (startId == null) creationAcknowledged = true else lastAcknowledgedStart = startId
            }
        }, removeForeground, stopService)
    }

    /** Every foreground-start request must be acknowledged even when it will immediately stop.
     * Never starts session resources. Cleanup is attempted independently if the platform rejects
     * foreground promotion (e.g. a background bind with no foreground-start requirement).
     */
    fun reject(acknowledgeForeground: () -> Unit, removeForeground: () -> Unit, stopService: () -> Unit): Boolean {
        rejected = true
        var completed = true
        try { acknowledgeForeground() } catch (_: Exception) { completed = false }
        try { removeForeground() } catch (_: Exception) { completed = false }
        try { stopService() } catch (_: Exception) { completed = false }
        return completed
    }
}
