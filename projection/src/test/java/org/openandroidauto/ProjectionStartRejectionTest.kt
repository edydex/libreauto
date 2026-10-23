package org.openandroidauto

import org.junit.Assert.*
import org.junit.Test

class ProjectionStartRejectionTest {
    @Test fun rejectedStartAcknowledgesForegroundBeforeRemovingItAndStopping() {
        val rejection = ProjectionStartRejection()
        val effects = mutableListOf<String>()
        assertTrue(rejection.shouldReject(false))
        assertTrue(rejection.reject({ effects += "foreground" }, { effects += "remove" }, { effects += "stop" }))
        assertEquals(listOf("foreground", "remove", "stop"), effects)
    }
    @Test fun modeChangeBetweenOnCreateAndQueuedOnStartCannotReviveARejectedInstance() {
        val rejection = ProjectionStartRejection()
        assertFalse(rejection.shouldReject(true))
        rejection.reject({}, {}, {})
        assertTrue(rejection.shouldReject(true))
        assertTrue(rejection.shouldReject(false))
    }
    @Test fun eachQueuedForegroundStartIsAcknowledgedWithoutSessionWork() {
        val rejection = ProjectionStartRejection()
        val effects = mutableListOf<String>()
        repeat(2) { rejection.reject({ effects += "foreground" }, { effects += "remove" }, { effects += "stop" }) }
        assertEquals(listOf("foreground", "remove", "stop", "foreground", "remove", "stop"), effects)
    }
    @Test fun deniedPromotionStillRemovesAndStopsAndDoesNotClaimAcknowledgement() {
        val rejection = ProjectionStartRejection()
        val effects = mutableListOf<String>()
        assertFalse(rejection.reject({ throw SecurityException("denied") }, { effects += "remove" }, { effects += "stop" }))
        assertEquals(listOf("remove", "stop"), effects)
        assertTrue(rejection.rejected)
    }
    @Test fun removingForegroundFailureStillAttemptsStop() {
        val rejection = ProjectionStartRejection()
        var stopped = false
        assertFalse(rejection.reject({}, { throw IllegalStateException("remove") }, { stopped = true }))
        assertTrue(stopped)
    }
    @Test fun creationAndItsFirstStartShareOneAcknowledgement() {
        val rejection = ProjectionStartRejection()
        val effects = mutableListOf<String>()
        rejection.rejectStart(null, { effects += "foreground" }, { effects += "remove" }, { effects += "stop-create" })
        rejection.rejectStart(1, { effects += "foreground" }, { effects += "remove" }, { effects += "stop-1" })
        assertEquals(listOf("foreground", "remove", "stop-create", "remove", "stop-1"), effects)
    }
    @Test fun aNewStartIdAcknowledgesANewForegroundRequestWhileDuplicateDeliveryDoesNot() {
        val rejection = ProjectionStartRejection()
        var acknowledged = 0
        rejection.rejectStart(null, { acknowledged++ }, {}, {})
        rejection.rejectStart(1, { acknowledged++ }, {}, {})
        rejection.rejectStart(1, { acknowledged++ }, {}, {})
        rejection.rejectStart(2, { acknowledged++ }, {}, {})
        assertEquals(2, acknowledged)
    }
    @Test fun failedCreationAcknowledgementIsRetriedAtTheFirstStartCommand() {
        val rejection = ProjectionStartRejection()
        assertFalse(rejection.rejectStart(null, { throw SecurityException("not allowed") }, {}, {}))
        var acknowledged = false
        assertTrue(rejection.rejectStart(1, { acknowledged = true }, {}, {}))
        assertTrue(acknowledged)
    }
    @Test fun rejectionFirstOccurringInOnStartStillAcknowledgesBeforeStop() {
        val rejection = ProjectionStartRejection()
        val effects = mutableListOf<String>()
        rejection.rejectStart(7, { effects += "foreground" }, { effects += "remove" }, { effects += "stop" })
        assertEquals(listOf("foreground", "remove", "stop"), effects)
    }
}
