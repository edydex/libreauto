package org.openandroidauto

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ProjectionSessionRunnerTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runAll() { while (work.isNotEmpty()) work.removeFirst().run() }
    }

    @Test fun cancellationBeforeBodyDispatchStillCleansUpExactlyOnceThenCompletes() {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val stages = mutableListOf<String>()
        val job = ProjectionSessionRunner(scope).launch(
            { stages += "body" }, { stages += "cleanup" }, { stages += "joined" })
        job.cancel(); dispatcher.runAll()
        assertTrue(job.isCompleted)
        assertEquals(listOf("cleanup", "joined"), stages)
        scope.cancel(); dispatcher.runAll()
    }

    @Test fun finalizerAndJoinedCallbackWaitForBlockedSessionChild() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cleanup = CompletableDeferred<Unit>(); val joined = CompletableDeferred<Unit>()
        val job = ProjectionSessionRunner(scope).launch({
            coroutineScope {
                launch { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                awaitCancellation()
            }
        }, { cleanup.complete(Unit) }, { joined.complete(Unit) })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            job.cancel()
            assertFalse(job.isActive); assertFalse(job.isCompleted)
            assertFalse(cleanup.isCompleted); assertFalse(joined.isCompleted)
            release.countDown()
            withTimeout(2_000) { joined.await() }
            assertTrue(job.isCompleted); assertTrue(cleanup.isCompleted)
        } finally { release.countDown(); withContext(NonCancellable) { job.join() }; scope.cancel() }
    }

    @Test fun joinedCallbackDoesNotRunWhileCleanupItselfIsStillPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cleanupEntered = CompletableDeferred<Unit>(); val releaseCleanup = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val job = ProjectionSessionRunner(scope).launch({}, {
            cleanupEntered.complete(Unit); releaseCleanup.await()
        }, { joined.complete(Unit) })
        try {
            withTimeout(2_000) { cleanupEntered.await() }
            job.cancel()
            assertFalse(job.isActive); assertFalse(job.isCompleted); assertFalse(joined.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(2_000) { joined.await() }
            assertTrue(job.isCompleted)
        } finally { releaseCleanup.complete(Unit); withContext(NonCancellable) { job.join() }; scope.cancel() }
    }
}
