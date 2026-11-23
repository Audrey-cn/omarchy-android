// SPDX-License-Identifier: GPL-3.0-or-later
package com.fct.tc4.install

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InitialDesktopWorkQueueTest {
    private fun await(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for controlled worker step" }
    }

    @Test(timeout = 15_000) fun idleCancelReturnsBeforeTraversalAndPendingStartWaitsForCleanup() {
        val root = Files.createTempDirectory("initial-work-queue-test").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val callbackExecutor = Executors.newSingleThreadExecutor()
        val releaseCleanup = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        try {
            val data = File(root, "data").apply { mkdir() }
            val staging = File(root, "staging").apply { mkdir() }
            val interrupted = File(staging, "image-00000000-0000-4000-8000-000000000001").apply { mkdir() }
            File(interrupted, "unfinished-rootfs").writeText("owned extraction")
            File(staging, interrupted.name + ".owner").writeText("initial-staging-v1\t${interrupted.name}\n")
            val transaction = InitialInstallTransaction(data, staging)
            val running = AtomicBoolean(false)
            val cleanupEntered = CountDownLatch(1)
            val installEntered = CountDownLatch(1)
            val idle = CountDownLatch(1)
            val idleCalls = AtomicInteger()
            val idleSawRunning = AtomicBoolean(true)
            val idleHeldControlLock = AtomicBoolean(false)
            val controlLock = Any()
            val callbackThread = AtomicReference<Thread>()
            val cleanupThread = AtomicReference<Thread>()
            val failure = AtomicReference<Throwable>()
            val events = ConcurrentLinkedQueue<String>()
            val queue = InitialDesktopWorkQueue(executor, running, controlLock, onIdle = {
                idleHeldControlLock.set(Thread.holdsLock(controlLock))
                idleSawRunning.set(running.get())
                idleCalls.incrementAndGet()
                events.add("idle")
                idle.countDown()
            })
            // The Future models a service callback: it must return while deletion is held.
            val cancelReturned = callbackExecutor.submit<Boolean> {
                callbackThread.set(Thread.currentThread())
                queue.cancelIdle {
                    cleanupThread.set(Thread.currentThread())
                    cleanupEntered.countDown()
                    try {
                        await(releaseCleanup)
                        transaction.discardInterruptedStages()
                        events.add("cleaned")
                    } catch (error: Throwable) { failure.compareAndSet(null, error) }
                }
            }
            assertTrue(cancelReturned.get(2, TimeUnit.SECONDS))
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS))
            assertNotSame("Recursive cleanup must run off the service callback thread", callbackThread.get(), cleanupThread.get())
            assertTrue("Cancel must reserve the running gate before returning", running.get())
            assertTrue("The held cleanup has not traversed the tree yet", interrupted.exists())
            assertTrue(queue.start {
                try {
                    if (interrupted.exists()) throw AssertionError("Install raced the interrupted-stage cleanup")
                    events.add("install")
                    installEntered.countDown()
                    await(releaseInstall)
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
            })
            assertFalse("Only one pending START is retained while cancellation runs", queue.start { events.add("duplicate") })
            assertEquals(1L, installEntered.count)
            assertEquals(0, idleCalls.get())
            releaseCleanup.countDown()
            assertTrue(installEntered.await(2, TimeUnit.SECONDS))
            assertFalse(interrupted.exists())
            assertTrue("Running must stay true across cleanup-to-install handoff", running.get())
            assertEquals(0, idleCalls.get())
            releaseInstall.countDown()
            assertTrue(idle.await(2, TimeUnit.SECONDS))
            assertNull(failure.get())
            assertFalse(running.get())
            assertFalse(idleSawRunning.get())
            assertTrue("Idle/start-id finalization must retain the shared control lock", idleHeldControlLock.get())
            assertEquals(1, idleCalls.get())
            assertEquals(listOf("cleaned", "install", "idle"), events.toList())
        } finally {
            releaseCleanup.countDown()
            releaseInstall.countDown()
            executor.shutdownNow()
            callbackExecutor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            callbackExecutor.awaitTermination(2, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    @Test(timeout = 10_000) fun startAcceptedWhileIdleCancellationIsStillQueuedIsNotLost() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseExecutor = CountDownLatch(1)
        try {
            val blockerEntered = CountDownLatch(1)
            executor.execute { blockerEntered.countDown(); await(releaseExecutor) }
            assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))
            val running = AtomicBoolean(false)
            val idle = CountDownLatch(1)
            val idleCalls = AtomicInteger()
            val events = ConcurrentLinkedQueue<String>()
            val queue = InitialDesktopWorkQueue(executor, running, onIdle = {
                events.add("idle")
                idleCalls.incrementAndGet()
                idle.countDown()
            })
            assertTrue(queue.cancelIdle { events.add("cleanup") })
            assertTrue(running.get())
            assertTrue(queue.start { events.add("install") })
            assertTrue("Neither queued operation may run on the caller", events.isEmpty())
            releaseExecutor.countDown()
            assertTrue(idle.await(2, TimeUnit.SECONDS))
            assertFalse(running.get())
            assertEquals(1, idleCalls.get())
            assertEquals(listOf("cleanup", "install", "idle"), events.toList())
        } finally {
            releaseExecutor.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 10_000) fun activeInstallRejectsAdditionalStartAndIdleCancel() {
        val executor = Executors.newSingleThreadExecutor()
        val releaseInstall = CountDownLatch(1)
        try {
            val entered = CountDownLatch(1)
            val idle = CountDownLatch(1)
            val running = AtomicBoolean(false)
            val events = ConcurrentLinkedQueue<String>()
            val queue = InitialDesktopWorkQueue(executor, running, onIdle = { idle.countDown() })
            assertTrue(queue.start {
                events.add("original install")
                entered.countDown()
                await(releaseInstall)
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(queue.start { events.add("duplicate install") })
            assertFalse(queue.cancelIdle { events.add("unexpected cleanup") })
            assertTrue(running.get())
            releaseInstall.countDown()
            assertTrue(idle.await(2, TimeUnit.SECONDS))
            assertFalse(running.get())
            assertEquals(listOf("original install"), events.toList())
        } finally {
            releaseInstall.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
