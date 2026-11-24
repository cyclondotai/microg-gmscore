/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.checkin

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.locks.ReentrantLock

/**
 * One writer of the check-in identity at a time across microG's processes (CYC-2935). It is held around local writes only,
 * never across a network call, so a factory-baseline restore never waits for a check-in.
 *
 * The file is opened only while [inProcess] is held: Linux drops a process's file locks when any descriptor of the file
 * closes, and a JVM refuses overlapping locks. The OS releases the lock when its process dies.
 */
object CheckinLock {
    const val FILE_NAME = "cyclon_checkin.lock"

    private val inProcess = ReentrantLock()

    /** For tests: whether a thread of this process is waiting for the lock. */
    internal val hasWaiters get() = inProcess.hasQueuedThreads()

    class Held internal constructor(private val channel: FileChannel) {
        /** Call once, on the thread that acquired it. */
        @Throws(IOException::class)
        fun release() {
            try {
                channel.close()
            } finally {
                inProcess.unlock()
            }
        }
    }

    /** Waits for the lock. Holders do only local writes, so the wait is short. */
    @JvmStatic
    @Throws(IOException::class)
    fun acquire(file: File): Held {
        check(!inProcess.isHeldByCurrentThread) { "Check-in lock is not reentrant" }
        inProcess.lock()
        return open(file) { it.lock() } ?: throw IOException("Check-in lock unavailable")
    }

    /** The lock, or null at once when another thread or process holds it. */
    @JvmStatic
    @Throws(IOException::class)
    fun tryAcquire(file: File): Held? {
        if (inProcess.isHeldByCurrentThread || !inProcess.tryLock()) return null
        return open(file) { it.tryLock() }
    }

    private fun open(file: File, take: (FileChannel) -> FileLock?): Held? {
        var channel: FileChannel? = null
        var held = false
        try {
            channel = RandomAccessFile(file, "rw").channel
            if (take(channel) == null) return null
            held = true
            return Held(channel)
        } finally {
            if (!held) {
                try {
                    channel?.close()
                } finally {
                    inProcess.unlock()
                }
            }
        }
    }
}
