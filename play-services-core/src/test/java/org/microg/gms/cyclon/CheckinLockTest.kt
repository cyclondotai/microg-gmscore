/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.microg.gms.checkin.CheckinLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask

/** Plain JVM tests with real child processes: the lock is what keeps microG's processes from racing each other. */
class CheckinLockTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = folder.root.resolve(CheckinLock.FILE_NAME)

    @Test(timeout = 60_000) fun anotherProcessHoldingItRefusesTryAcquireAtOnce() {
        val child = LockChild.hold(file)
        try {
            assertNull(CheckinLock.tryAcquire(file))
        } finally {
            child.finish()
        }
        val held = assertNotNull(CheckinLock.tryAcquire(file))
        held.release()
    }

    @Test(timeout = 60_000) fun aProcessThatDiesReleasesIt() {
        val child = LockChild.hold(file)
        assertNull(CheckinLock.tryAcquire(file))
        child.kill()
        val held = assertNotNull(CheckinLock.tryAcquire(file))
        held.release()
    }

    @Test(timeout = 60_000) fun itExcludesAnotherProcessWhileHeldHere() {
        val held = CheckinLock.acquire(file)
        try {
            assertFalse(LockChild.isFree(file))
        } finally {
            held.release()
        }
        assertTrue(LockChild.isFree(file))
    }

    /** Linux drops a process's lock when any descriptor of the file closes, so a refused attempt must not open it. */
    @Test(timeout = 60_000) fun aRefusedAttemptInThisProcessKeepsTheLock() {
        val held = CheckinLock.acquire(file)
        try {
            val attempt = FutureTask { CheckinLock.tryAcquire(file) }
            Thread(attempt).start()
            assertNull(attempt.get())
            assertNull(CheckinLock.tryAcquire(file))
            assertFalse(LockChild.isFree(file))
        } finally {
            held.release()
        }
    }

    @Test(timeout = 60_000) fun aSecondThreadWaitsAndThenGetsIt() {
        val held = CheckinLock.acquire(file)
        val got = CountDownLatch(1)
        val waiter = Thread {
            val second = CheckinLock.acquire(file)
            got.countDown()
            second.release()
        }.apply { start() }
        while (!CheckinLock.hasWaiters) Thread.onSpinWait()
        assertEquals(1, got.count)
        held.release()
        got.await()
        waiter.join()
    }

    @Test(timeout = 60_000) fun aNestedAcquireFailsInsteadOfDroppingTheLock() {
        val held = CheckinLock.acquire(file)
        try {
            assertThrows(IllegalStateException::class.java) { CheckinLock.acquire(file) }
            assertFalse(LockChild.isFree(file))
        } finally {
            held.release()
        }
    }

    @Test(timeout = 60_000) fun aFailedOpenLeavesItFree() {
        val missing = folder.root.resolve("missing/${CheckinLock.FILE_NAME}")
        assertThrows(java.io.IOException::class.java) { CheckinLock.acquire(missing) }
        assertThrows(java.io.IOException::class.java) { CheckinLock.tryAcquire(missing) }
        val held = assertNotNull(CheckinLock.tryAcquire(file))
        held.release()
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        assertNotNull(null, value)
        return value!!
    }
}
