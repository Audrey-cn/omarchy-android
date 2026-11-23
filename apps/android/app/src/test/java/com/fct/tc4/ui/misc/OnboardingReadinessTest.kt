package com.fct.tc4.ui.misc

import com.fct.tc4.ui.misc.OnboardingReadiness.State
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingReadinessTest {
    private val viewport = OnboardingViewport(2340, 1080)
    private val desktop = OnboardingDesktop(
        x11SocketInode = 11,
        swaySocketInode = 22,
        swayPid = 141,
        waybarPid = 143,
        waybarStartTicks = 1500,
        output = OnboardingOutput(1560, 720, 28)
    )

    @Test fun connectedViewportAloneTimesOutWithoutCompletingOnboarding() {
        val readiness = OnboardingReadiness(startedAt = 0)

        assertEquals(State.WAITING, readiness.observe(0, viewport, null))
        assertEquals(State.WAITING, readiness.observe(29_999, viewport, null))
        assertEquals(State.TIMED_OUT, readiness.observe(30_000, viewport, null))
        assertFalse(readiness.finish())
    }

    @Test fun desktopWithoutAConnectedLaidOutViewportCannotStartTheGuide() {
        val readiness = OnboardingReadiness(startedAt = 0)

        assertEquals(State.WAITING, readiness.observe(0, null, desktop))
        assertEquals(State.TIMED_OUT, readiness.observe(30_000, null, desktop))
    }

    @Test fun waitsForStableDesktopThenOnlyCompletesAfterAnExplicitFinish() {
        val readiness = OnboardingReadiness(startedAt = 0)

        assertEquals(State.WAITING, readiness.observe(0, viewport, desktop))
        assertFalse(readiness.finish())
        assertEquals(State.WAITING, readiness.observe(899, viewport, desktop))
        assertEquals(State.READY, readiness.observe(900, viewport, desktop))
        assertEquals(State.READY, readiness.state)
        assertTrue(readiness.finish())
        assertEquals(State.FINISHED, readiness.state)
        assertFalse(readiness.finish())
    }

    @Test fun changingDesktopOrViewportRestartsTheStabilityWindow() {
        val readiness = OnboardingReadiness(startedAt = 0)
        val newDesktop = desktop.copy(swayPid = 149, waybarPid = 153)
        val newViewport = OnboardingViewport(1080, 2340)

        assertEquals(State.WAITING, readiness.observe(0, viewport, desktop))
        assertEquals(State.WAITING, readiness.observe(800, viewport, newDesktop))
        assertEquals(State.WAITING, readiness.observe(1600, newViewport, newDesktop))
        assertEquals(State.WAITING, readiness.observe(2000, null, newDesktop))
        assertEquals(State.WAITING, readiness.observe(2100, newViewport, newDesktop))
        assertEquals(State.READY, readiness.observe(3000, newViewport, newDesktop))
    }

    @Test fun detachDiscardsAnInFlightResultAndRetryRequiresFreshSamples() {
        val first = OnboardingReadiness(startedAt = 0)
        first.observe(0, viewport, desktop)
        first.cancel()
        assertEquals(State.CANCELLED, first.observe(1_000, viewport, desktop))
        assertFalse(first.finish())

        val retry = OnboardingReadiness(startedAt = 1_000)
        assertEquals(State.WAITING, retry.observe(1_000, viewport, desktop))
        assertEquals(State.READY, retry.observe(1_900, viewport, desktop))
    }

    @Test fun lateResultAfterTimeoutCannotOpenTheGuide() {
        val readiness = OnboardingReadiness(startedAt = 0)
        readiness.observe(29_000, viewport, desktop)

        assertEquals(State.TIMED_OUT, readiness.observe(30_001, viewport, desktop))
        assertFalse(readiness.finish())
    }

    @Test fun expiredSampleClosesTransportAndLateSuccessCannotReplaceRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val initial = async {
            sampleOnboardingBefore(500, close = { closed.complete(Unit) }) {
                entered.complete(Unit)
                try {
                    release.await(3, TimeUnit.SECONDS)
                    "stale desktop"
                } finally {
                    finished.complete(Unit)
                }
            }
        }

        try {
            withTimeout(2_000) {
                entered.await()
                assertNull(initial.await())
                closed.await()
                assertFalse(finished.isCompleted)
                assertEquals("current desktop", sampleOnboardingBefore(500, close = {}) { "current desktop" })
            }
        } finally {
            release.countDown()
        }
        withTimeout(2_000) { finished.await() }
        assertNull(initial.await())
    }

    @Test fun explicitSkipOptsOutWithoutCompletingAndManualReplayCanFinish() {
        val initial = OnboardingProgress(completed = false, optedOut = false)
        assertTrue(initial.shouldShowAutomatically)

        val skipped = initial.after(OnboardingExit.SKIP)
        assertTrue(skipped.optedOut)
        assertFalse(skipped.completed)
        assertFalse(skipped.shouldShowAutomatically)
        val completed = skipped.after(OnboardingExit.COMPLETE)
        assertTrue(completed.completed)
        assertFalse(completed.shouldShowAutomatically)
    }

    @Test fun shortcutPreviewAndReplayPreserveActualCompletionState() {
        val initial = OnboardingProgress(completed = false, optedOut = false)
        assertEquals(initial, initial.after(OnboardingExit.PREVIEW))
        val completed = OnboardingProgress(completed = true, optedOut = false)
        assertFalse(completed.shouldShowAutomatically)
        assertEquals(completed, completed.after(OnboardingExit.PREVIEW))
        assertEquals(completed, completed.after(OnboardingExit.SKIP))
    }
}
