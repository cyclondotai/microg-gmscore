/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import org.microg.gms.checkin.CheckinLock
import java.io.BufferedReader
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/** A real second JVM around [CheckinLock]: `hold` keeps the lock until stdin closes, `probe` reports whether it is free. */
internal class LockChild private constructor(private val process: Process, private val output: BufferedReader) {
    fun readLine(): String? = output.readLine()

    /** Ends the child normally and waits for it, so the lock is free when this returns. */
    fun finish() {
        process.outputStream.close()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Lock child did not exit" }
    }

    /** Kills the child without letting it release anything. */
    fun kill() {
        process.destroyForcibly()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Lock child did not die" }
    }

    companion object {
        private fun root(name: String): String {
            val path = "${name.replace('.', '/')}.class"
            val url: URL = checkNotNull(LockChild::class.java.classLoader!!.getResource(path)) { "No $path" }
            return when (url.protocol) {
                "file" -> File(url.toURI()).path.removeSuffix(path).removeSuffix(File.separator)
                "jar" -> File(URL(url.path.substringBefore("!")).toURI()).path
                else -> error("Unexpected class location")
            }
        }

        private fun start(mode: String, file: File): LockChild {
            val classPath = listOf(
                "org.microg.gms.checkin.CheckinLock", "org.microg.gms.cyclon.LockChildMain", "kotlin.jvm.internal.Intrinsics",
            ).map(::root).distinct().joinToString(File.pathSeparator)
            val java = File(System.getProperty("java.home"), "bin/java").path
            val process = ProcessBuilder(java, "-Xshare:auto", "-cp", classPath, "org.microg.gms.cyclon.LockChildMain", mode, file.path)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            return LockChild(process, process.inputStream.bufferedReader())
        }

        /** Returns once the child reports that it holds the lock. */
        fun hold(file: File): LockChild {
            val child = start("hold", file)
            check(child.readLine() == "locked") { "Lock child did not take the lock" }
            return child
        }

        /** Whether a separate process could take the lock right now. */
        fun isFree(file: File): Boolean {
            val child = start("probe", file)
            val answer = child.readLine()
            child.finish()
            return when (answer) {
                "free" -> true
                "taken" -> false
                else -> error("Lock child gave no answer")
            }
        }
    }
}

internal object LockChildMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = File(args[1])
        if (args[0] == "probe") {
            val held = CheckinLock.tryAcquire(file)
            println(if (held != null) "free" else "taken")
            System.out.flush()
            held?.release()
            return
        }
        val held = CheckinLock.acquire(file)
        println("locked")
        System.out.flush()
        while (System.`in`.read() >= 0) Unit
        held.release()
    }
}
