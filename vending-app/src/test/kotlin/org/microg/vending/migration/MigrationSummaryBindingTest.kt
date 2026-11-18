/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor

/**
 * CYC-2573: a summary asks Aurora about every open app over one binding (it used to bind and unbind once per app), and
 * its receipts stay exactly what they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationSummaryBindingTest {
    private val app get() = RuntimeEnvironment.getApplication()

    /** Counts the bridge's binds and unbinds; everything else is the application's. */
    private class CountingContext(base: Context) : ContextWrapper(base) {
        var binds = 0
        var unbinds = 0
        override fun bindService(service: Intent, flags: Int, executor: Executor, conn: ServiceConnection): Boolean {
            binds++; return super.bindService(service, flags, executor, conn)
        }
        override fun unbindService(conn: ServiceConnection) { unbinds++; super.unbindService(conn) }
    }

    /** Aurora's MigrationStoreService wire for status checks (transaction 3), answering with [answer]. */
    private class FakeStore(private val answer: (String) -> Bundle) : Binder() {
        init { attachInterface(null, AuroraMigrationClient.DESCRIPTOR) }
        val asked = mutableListOf<String>()
        /** The call (counted from 1) that throws, as a lost or failing Aurora would. */
        var failOnCall: Int? = null
        var alive = true
        override fun isBinderAlive() = alive
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 3) return false
            data.enforceInterface(AuroraMigrationClient.DESCRIPTOR)
            val name = requireNotNull(data.readString())
            asked += name
            check(asked.size != failOnCall) { "Aurora failed on $name" }
            requireNotNull(reply).writeNoException(); reply.writeInt(1); answer(name).writeToParcel(reply, 0)
            return true
        }
    }

    @Before fun clear() { app.getSharedPreferences("migration-installs", 0).edit().clear().commit() }

    private fun installAurora(store: Binder) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.aurora.store"
            applicationInfo = ApplicationInfo().apply { packageName = "com.aurora.store"; flags = ApplicationInfo.FLAG_SYSTEM }
        })
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent("com.aurora.store.migration.BIND").setPackage("com.aurora.store"),
            ComponentName("com.aurora.store", "com.aurora.store.appfunctions.MigrationStoreService"), store)
    }

    private fun doc(name: String, version: Long) = Bundle().apply {
        putString("package_name", name); putString("title", "Title of $name"); putLong("version_code", version)
        putInt("availability", 1)
    }

    private fun status(name: String, status: String, installed: Boolean = false, version: Long = 0, offer: Long = 12) = Bundle().apply {
        putString("package_name", name); putString("title", "Aurora's $name"); putString("icon_url", "https://example.org/$name.png")
        putString("status", status); putBoolean("installed", installed); putLong("installed_version_code", version)
        putLong("version_code", offer)
    }

    /** One line per receipt: its keys sorted, each value with its type, so an Int for a Long shows too. */
    private fun render(summary: Bundle): String = summary.getParcelableArray("cyclon_app_results")!!.joinToString("") { parcel ->
        val result = parcel as Bundle
        result.keySet().sorted().joinToString(" ", postfix = "\n") { key ->
            @Suppress("DEPRECATION") val value = result.get(key)
            "$key=${value?.javaClass?.simpleName}:$value"
        }
    }

    private val three = listOf("org.example.one", "org.example.two", "org.example.three")
    private fun threeAnswers(name: String) = when (name) {
        "org.example.one" -> status(name, "downloading").apply { putInt("progress", 40) }
        "org.example.two" -> status(name, "queued")
        else -> status(name, "installed", installed = true, version = 12)
    }
    private fun selectThree(queue: MigrationInstallQueue) = queue.select(three.map { doc(it, 12) })

    /** The same receipts as with one Aurora call per app (the storeCall hook stands in for bridge()). */
    private fun expectedThree(answer: (String) -> Bundle): String {
        clear()
        val queue = MigrationInstallQueue(app, { _, _ -> Bundle() }) { operation, name ->
            check(operation == 3); answer(name)
        }
        selectThree(queue)
        return render(queue.summary()).also { clear() }
    }

    @Test fun aSummaryAsksEveryOpenAppOverOneBinding() {
        val expected = expectedThree(::threeAnswers)
        val store = FakeStore(::threeAnswers).also(::installAurora)
        val context = CountingContext(app)
        val queue = MigrationInstallQueue(context, { _, _ -> Bundle() })
        selectThree(queue)
        assertEquals(expected, render(queue.summary()))
        assertEquals(three.sorted(), store.asked.sorted())
        assertEquals(1, context.binds)
        assertEquals(1, context.unbinds)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
        // bridge() itself, as dispatch() uses it, still binds once per call.
        for (name in three) assertEquals(threeAnswers(name).getString("status"), queue.bridge(3, name).getString("status"))
        assertEquals(4, context.binds)
        assertEquals(4, context.unbinds)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
    }

    @Test fun aFailedCallOnlyFailsItsAppAndTheNextAppBindsAgain() {
        val store = FakeStore(::threeAnswers).apply { failOnCall = 2 }.also(::installAurora)
        val context = CountingContext(app)
        val queue = MigrationInstallQueue(context, { _, _ -> Bundle() })
        selectThree(queue)
        val summary = queue.summary()
        assertEquals(3, store.asked.size)
        val (_, failed, after) = store.asked
        assertEquals(2, context.binds)
        assertEquals(2, context.unbinds)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        val results = summary.getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.associateBy { it.getString("package_name") }
        assertEquals("store_unavailable", results.getValue(failed).getString("error"))
        assertEquals(threeAnswers(after).getString("status"), results.getValue(after).getString("status"))
        // The failed app's receipt is what bridge() gave it: store_unavailable, and the others their own answers.
        val rendered = render(summary)
        assertEquals(expectedThree { name ->
            if (name == failed) MigrationInstallQueue.unavailable(name, "store_unavailable") else threeAnswers(name)
        }, rendered)
    }

    @Test fun aDeadAuroraIsBoundAgainBeforeTheNextApp() {
        lateinit var store: FakeStore
        // Aurora dies after its first answer; the fake binder stays dead, so each later app binds again.
        store = FakeStore { name -> threeAnswers(name).also { store.alive = false } }
        installAurora(store)
        val context = CountingContext(app)
        val queue = MigrationInstallQueue(context, { _, _ -> Bundle() })
        selectThree(queue)
        val rendered = render(queue.summary())
        assertEquals(3, store.asked.size)
        assertEquals(3, context.binds)
        assertEquals(3, context.unbinds)
        assertTrue(shadowOf(app).boundServiceConnections.isEmpty())
        assertEquals(expectedThree(::threeAnswers), rendered)
    }

    /**
     * cyclon_app_results for a large Setup copy: 300 selected apps, 150 of them open (asked of Aurora), and 10 the copy
     * left out. Recorded before CYC-2573; the receipts must stay the same byte for byte.
     */
    @Test fun aLargeSummaryKeepsItsReceipts() {
        val golden = javaClass.getResourceAsStream("summary-300-golden.txt")?.bufferedReader()?.readText()
        val viaHook = goldenRun(binder = false)
        val viaBinder = goldenRun(binder = true)
        if (golden != viaHook || golden != viaBinder) {
            File("build/golden").mkdirs()
            File("build/golden/summary-300-hook.txt").writeText(viaHook)
            File("build/golden/summary-300-binder.txt").writeText(viaBinder)
        }
        assertNotNull("No golden: see build/golden", golden)
        assertEquals(golden, viaHook)
        assertEquals(golden, viaBinder)
    }

    private fun goldenName(i: Int) = "org.golden.app%03d".format(i)
    private fun goldenExpected(i: Int) = 100L + i % 5

    /** Aurora's answer for an open app, by its index: a spread over summarize()'s branches. */
    private fun goldenAnswer(name: String): Bundle {
        val i = name.takeLast(3).toInt()
        val expected = goldenExpected(i)
        return when (i / 2 % 10) {
            0 -> status(name, "none", offer = 0)
            1 -> status(name, "queued", offer = expected)
            2 -> status(name, "downloading", offer = expected).apply { putInt("progress", i % 100) }
            3 -> status(name, "installed", installed = true, version = expected, offer = expected)
            4 -> status(name, "failed", offer = expected).apply { putString("error", "failed"); putString("errorCode", "app_not_purchased") }
            5 -> status(name, "awaiting_install", offer = expected)
            6 -> status(name, "installed", installed = true, version = expected - 1, offer = expected - 1).apply { putBoolean("package_present", true) }
            7 -> status(name, "installed", offer = expected).apply { putBoolean("package_present", false) }
            8 -> status(name, "none", offer = 0).apply { putString("title", name); putString("icon_url", "") }
            else -> MigrationInstallQueue.unavailable(name, "store_unavailable")
        }
    }

    /** The install request's answer for a closed app, by its index. */
    private fun goldenInstall(name: String): Bundle {
        val i = name.takeLast(3).toInt()
        return when (i % 3) {
            0 -> status(name, "installed", installed = true, version = goldenExpected(i), offer = goldenExpected(i)).apply { putBoolean("terminal", true) }
            1 -> MigrationInstallQueue.unavailable(name, "paid_app")
            else -> status(name, "failed").apply { putString("error", "download_failed"); putBoolean("terminal", true) }
        }
    }

    private fun goldenRun(binder: Boolean): String {
        clear()
        val names = (0 until 300).map(::goldenName)
        val left = (0 until 10).map { "org.golden.left%02d".format(it) }
        val capabilities = Bundle().apply { putBoolean("owner_account_fallback", true) }
        // Closes half the apps over the storeCall hook (their install requests settle them for good), and hands a quarter
        // to Aurora still open (no longer pending); the remaining quarter stays pending.
        val closer = MigrationInstallQueue(app, { _, _ -> capabilities }) { operation, name ->
            val i = name.takeLast(3).toInt()
            when {
                operation == 3 -> status(name, "none", offer = 0)
                i % 2 == 0 -> status(name, "queued", offer = goldenExpected(i))
                else -> goldenInstall(name)
            }
        }
        closer.recordCatalog(names.map { doc(it, goldenExpected(it.takeLast(3).toInt())) } + left.mapIndexed { i, name ->
            doc(name, 7).apply { putInt("availability", 2); putBoolean("recoverable", i % 2 == 0) }
        })
        closer.select(names.mapIndexed { i, name -> doc(name, goldenExpected(i)) })
        for (i in names.indices step 2) assertTrue(closer.dispatch(names[i + 1]))
        for (i in names.indices step 4) assertTrue(closer.dispatch(names[i]))
        val queue = if (binder) {
            installAurora(FakeStore(::goldenAnswer))
            MigrationInstallQueue(CountingContext(app), { _, _ -> capabilities })
        } else MigrationInstallQueue(app, { _, _ -> capabilities }) { operation, name -> check(operation == 3); goldenAnswer(name) }
        val first = render(queue.summary())
        val second = render(queue.summary())
        return "# first summary\n$first# second summary\n$second"
    }
}
