package com.fct.tc4.ui.misc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class OnboardingViewport(val width: Int, val height: Int)

internal data class OnboardingOutput(val width: Int, val height: Int, val topReservation: Int)

internal data class OnboardingDesktop(
    val x11SocketInode: Long,
    val swaySocketInode: Long,
    val swayPid: Int,
    val waybarPid: Int,
    val waybarStartTicks: Long,
    val output: OnboardingOutput
)

internal enum class OnboardingExit { SKIP, PREVIEW, COMPLETE }

internal data class OnboardingProgress(val completed: Boolean, val optedOut: Boolean) {
    val shouldShowAutomatically: Boolean get() = !completed && !optedOut

    fun stepToResume(savedStep: Int, visibleStep: Int?, manualReplay: Boolean): Int? = when {
        visibleStep != null && visibleStep in 0..3 -> visibleStep
        (shouldShowAutomatically || manualReplay) && savedStep in 0..3 -> savedStep
        else -> null
    }

    fun after(action: OnboardingExit): OnboardingProgress = when (action) {
        OnboardingExit.SKIP -> if (completed) this else copy(optedOut = true)
        OnboardingExit.PREVIEW -> this
        OnboardingExit.COMPLETE -> copy(completed = true)
    }
}

internal suspend fun <T> sampleOnboardingBefore(timeoutMillis: Long, close: () -> Unit, sample: () -> T?): T? =
    withTimeoutOrNull(timeoutMillis) {
        // The UI deadline must be independent of a blocking system call. Closing the
        // active socket also wakes the worker on timeout or Activity detach.
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { close() }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (continuation.isActive) continuation.resumeWith(runCatching(sample))
            })
        }
    }

internal class OnboardingReadiness(private val startedAt: Long) {
    enum class State { WAITING, TIMED_OUT, READY, CANCELLED, FINISHED }

    private var stableSample: Pair<OnboardingViewport, OnboardingDesktop>? = null
    private var stableSince = startedAt
    var state = State.WAITING
        private set

    fun remainingMillis(now: Long): Long = (30_000 - (now - startedAt)).coerceAtLeast(0)

    fun observe(now: Long, viewport: OnboardingViewport?, desktop: OnboardingDesktop?): State {
        if (state != State.WAITING) return state
        if (remainingMillis(now) == 0L) {
            state = State.TIMED_OUT
            return state
        }

        val sample = if (viewport != null && desktop != null) viewport to desktop else null
        if (sample != stableSample) {
            stableSample = sample
            stableSince = now
        }
        if (sample != null && now - stableSince >= 900) state = State.READY
        return state
    }

    fun cancel() {
        state = State.CANCELLED
    }

    fun finish(): Boolean {
        if (state != State.READY) return false
        state = State.FINISHED
        return true
    }
}
