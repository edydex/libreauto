package org.openandroidauto

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ProjectionScopedSessionRunnerTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun cancelledBeforeFirstDispatchDoesNotAcquireIdentityAndStillCleansUp() {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val stages = mutableListOf<String>()
        val job = ProjectionSessionRunner(scope).launchScoped<Unit>(
            { stages += "body" }, { stages += "cleanup" }, { stages += "joined" },
            { body -> stages += "identity"; body(Unit) })
        job.cancel(); dispatcher.runAll()
        assertEquals(listOf("cleanup", "joined"), stages)
        assertTrue(job.isCompleted)
        scope.cancel(); dispatcher.runAll()
    }

    @Test fun hookLeaseEnclosesActionAndBlockedCleanupBeforeJoined() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val leaseOpen = AtomicBoolean()
        val cleanupEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val stages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val job = ProjectionSessionRunner(scope).launchScoped<String>({ value ->
            assertTrue(leaseOpen.get()); assertEquals("local-factory", value); stages += "body"
        }, {
            assertTrue(leaseOpen.get()); stages += "cleanup"; cleanupEntered.complete(Unit)
            release.await(); assertTrue(leaseOpen.get())
        }, { assertFalse(leaseOpen.get()); stages += "joined"; joined.complete(Unit) }, { body ->
            leaseOpen.set(true)
            try { body("local-factory") } finally { stages += "release"; leaseOpen.set(false) }
        })
        try {
            withTimeout(2_000) { cleanupEntered.await() }
            job.cancel()
            assertTrue(leaseOpen.get()); assertFalse(job.isCompleted); assertFalse(joined.isCompleted)
            release.complete(Unit)
            withTimeout(2_000) { joined.await() }
            assertEquals(listOf("body", "cleanup", "release", "joined"), stages)
        } finally { release.complete(Unit); withContext(NonCancellable) { job.join() }; scope.cancel() }
    }

    @Test fun refusedPreparationCleansServiceResourcesWithoutEnteringBody() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val joined = CompletableDeferred<Unit>()
        val job = ProjectionSessionRunner(scope).launchScoped<Unit>(
            { stages += "body" }, { stages += "cleanup" }, { stages += "joined"; joined.complete(Unit) },
            { stages += "refused" })
        try {
            withTimeout(2_000) { joined.await() }
            assertEquals(listOf("refused", "cleanup", "joined"), stages)
        } finally { job.join(); scope.cancel() }
    }

    @Test fun duplicateBodyInvocationDoesNotRunActionOrCleanupTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val joined = CompletableDeferred<Unit>()
        val job = ProjectionSessionRunner(scope).launchScoped<Unit>(
            { stages += "body" }, { stages += "cleanup" }, { joined.complete(Unit) },
            { body -> body(Unit); try { body(Unit); fail() } catch (_: IllegalStateException) { stages += "refused" } })
        try {
            withTimeout(2_000) { joined.await() }
            assertEquals(listOf("body", "cleanup", "refused"), stages)
        } finally { job.join(); scope.cancel() }
    }
}
