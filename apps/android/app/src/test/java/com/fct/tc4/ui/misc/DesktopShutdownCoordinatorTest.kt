package com.fct.tc4.ui.misc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DesktopShutdownCoordinatorTest {
    @Test fun repeatedStopKeepsResourcesAliveUntilTheSessionHasFlushed() = runBlocking {
        val flushed = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DesktopShutdownCoordinator(scope, { events += "service and activities" })
        val owner = Any()
        assertTrue(coordinator.claim(owner) { events += "term"; flushed.await(); events += "bridges" })
        val stopped = coordinator.requestStop()!!
        assertNull(coordinator.requestStop())
        // Clearing an Activity's VM cannot bypass the flush or release admission.
        coordinator.release(owner)
        assertFalse(coordinator.claim(Any()) { error("new session admitted during shutdown") })
        assertEquals(listOf("term"), events)
        flushed.complete(Unit)
        stopped.join()
        assertEquals(listOf("term", "bridges", "service and activities"), events)
        assertTrue(coordinator.claim(Any()) {})
        scope.cancel()
    }

    @Test fun clearingAnOldOwnerCannotRemoveTheNewSessionsStopHandler() = runBlocking {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DesktopShutdownCoordinator(scope, { events += "finish" })
        val old = Any()
        coordinator.claim(old) { events += "old" }
        coordinator.claim(Any()) { events += "new" }
        coordinator.release(old)
        coordinator.requestStop()!!.join()
        assertEquals(listOf("new", "finish"), events)
        scope.cancel()
    }

    @Test fun stopFailureStillFinishesTheServiceAndAllowsAFutureSession() = runBlocking {
        val events = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = DesktopShutdownCoordinator(scope, { events += "finish" }, { events += "error" })
        coordinator.claim(Any()) { throw IllegalStateException("bridge failed") }
        coordinator.requestStop()!!.join()
        assertEquals(listOf("error", "finish"), events)
        assertTrue(coordinator.claim(Any()) {})
        scope.cancel()
    }
}
