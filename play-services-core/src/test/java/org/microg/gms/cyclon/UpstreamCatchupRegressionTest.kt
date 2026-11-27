/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.auth.blockstore.RetrieveBytesResponse
import com.google.android.gms.auth.blockstore.internal.IRetrieveBytesCallback
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.data.DataHolder
import com.google.android.gms.people.internal.IPeopleCallbacks
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.auth.AskPermissionActivity
import org.microg.gms.auth.AuthConstants
import org.microg.gms.auth.blockstore.BlobstoreServiceImpl
import org.microg.gms.auth.blockstore.BlockStoreImpl
import org.microg.gms.location.reporting.fetchEffectiveAccountLocationSettings
import org.microg.gms.location.reporting.fetchEffectiveTimelineEnabled
import org.microg.gms.people.PeopleServiceImpl
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Regression tests for the v0.3.18.252432 upstream catch-up resolutions and stock-GMS-verified PRs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class UpstreamCatchupRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun askPermissionReadsBooleanExtraInsteadOfHasExtra() {
        assertFalse(AskPermissionActivity.isFromAccountManager(null))
        assertFalse(AskPermissionActivity.isFromAccountManager(Intent()))
        assertFalse(
            AskPermissionActivity.isFromAccountManager(
                Intent().putExtra(AskPermissionActivity.EXTRA_FROM_ACCOUNT_MANAGER, false)
            )
        )
        assertTrue(
            AskPermissionActivity.isFromAccountManager(
                Intent().putExtra(AskPermissionActivity.EXTRA_FROM_ACCOUNT_MANAGER, true)
            )
        )
    }

    @Test
    fun peopleLoadOwnersAllowsReadContactsCallersWhileHidingDeviceAccount() {
        val am = AccountManager.get(context)
        val owner = Account("owner@example.com", AuthConstants.DEFAULT_ACCOUNT_TYPE)
        val device = Account("cyclondevice@gmail.com", AuthConstants.DEFAULT_ACCOUNT_TYPE)
        shadowOf(am).addAccount(owner)
        shadowOf(am).addAccount(device)
        am.setUserData(device, DeviceAccount.MARKER, "1")

        val grantedContext = object : ContextWrapper(context) {
            override fun checkCallingPermission(permission: String): Int =
                if (permission == Manifest.permission.READ_CONTACTS) PackageManager.PERMISSION_GRANTED
                else PackageManager.PERMISSION_DENIED
        }

        var receivedStatus = -1
        var receivedMetadata: Bundle? = null
        val callbacks = object : IPeopleCallbacks.Stub() {
            override fun onDataHolder(statusCode: Int, resolution: Bundle?, dataHolder: DataHolder?) {
                receivedStatus = statusCode
                receivedMetadata = resolution?.getBundle("account_metadata")
                dataHolder?.close()
            }

            override fun onDataHolders(statusCode: Int, resolution: Bundle?, dataHolders: Array<out DataHolder>?) = Unit
            override fun onParcelFileDescriptor(statusCode: Int, resolution: Bundle?, pfd: android.os.ParcelFileDescriptor?, extras: Bundle?) = Unit
        }

        PeopleServiceImpl(grantedContext).loadOwners(callbacks, false, false, null, null, 0)
        assertEquals(0, receivedStatus)
        assertNotNull(receivedMetadata)
        assertTrue(receivedMetadata!!.containsKey(owner.name))
        assertFalse(receivedMetadata!!.containsKey(device.name))
    }

    @Test
    fun multiArchLoaderExtractsNativeLibraryReadOnlyForAndroid17() {
        val payload = "not-a-real-elf-but-valid-zip-payload".toByteArray()
        val crc = CRC32().apply { update(payload) }.value
        val apkFile = File(context.cacheDir, "fake-gms-${System.nanoTime()}.apk")
        ZipOutputStream(FileOutputStream(apkFile)).use { zos ->
            zos.putNextEntry(ZipEntry("lib/arm64-v8a/libmapbox-gl.so"))
            zos.write(payload)
            zos.closeEntry()
        }

        val appCache = File(context.cacheDir, "app-cache-${System.nanoTime()}").apply { mkdirs() }
        val mapCtx = object : ContextWrapper(context) {
            override fun getPackageCodePath(): String = apkFile.absolutePath
        }
        val appCtx = object : ContextWrapper(context) {
            override fun getCacheDir(): File = appCache
        }
        val loaderClass = runCatching { Class.forName("org.microg.gms.maps.mapbox.utils.MultiArchLoader") }.getOrNull()
        Assume.assumeNotNull("Mapbox flavor required for MultiArchLoader test", loaderClass)
        val loader = loaderClass!!.getConstructor(Context::class.java, Context::class.java).newInstance(mapCtx, appCtx)
        val target = File(appCache, ".gmscore/lib/arm64-v8a/${java.lang.Long.toHexString(crc)}-${payload.size}/libmapbox-gl.so")

        try {
            val extractMethod = loaderClass.getDeclaredMethod(
                "extract", File::class.java, String::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType, File::class.java
            ).apply { isAccessible = true }
            val extracted = extractMethod.invoke(loader, apkFile, "lib/arm64-v8a/libmapbox-gl.so", payload.size.toLong(), crc, target) as Boolean
            assertTrue(extracted)
            assertTrue(target.isFile)
            assertFalse("Extracted .so must be read-only for Android 17 (SDK 37+)", target.canWrite())

            // Simulate a writable copy extracted by an older build before #3883.
            // Lock the parent directory so attempt 2 cannot re-extract: this proves loadExtracted()'s target.setReadOnly()
            // flipped the pre-existing file rather than re-extracting a fresh copy.
            assertTrue(target.setWritable(true))
            assertTrue(target.canWrite())
            assertTrue(target.parentFile!!.setWritable(false))
            val loadExtractedMethod = loaderClass.getDeclaredMethod("loadExtracted", String::class.java)
                .apply { isAccessible = true }
            // System.load will fail on non-ELF payload, but loadExtracted must still flip the existing file read-only first.
            loadExtractedMethod.invoke(loader, "lib/arm64-v8a/libmapbox-gl.so")
            assertFalse("Pre-existing cached .so must be made read-only before System.load", target.canWrite())
        } finally {
            target.parentFile?.setWritable(true)
            apkFile.delete()
            appCache.deleteRecursively()
        }
    }

    @Test
    fun locationReportingHidesCyclonDeviceAccount() {
        val am = AccountManager.get(context)
        val owner = Account("owner@example.com", AuthConstants.DEFAULT_ACCOUNT_TYPE)
        val device = Account("cyclondevice@gmail.com", AuthConstants.DEFAULT_ACCOUNT_TYPE)
        val missing = Account("missing@example.com", AuthConstants.DEFAULT_ACCOUNT_TYPE)
        shadowOf(am).addAccount(owner)
        shadowOf(am).addAccount(device)
        am.setUserData(device, DeviceAccount.MARKER, "1")

        val missingSettings = fetchEffectiveAccountLocationSettings(
            context, missing, allowRemoteAccountSettings = true, refreshRemoteAccountSettings = false
        )
        val deviceSettings = fetchEffectiveAccountLocationSettings(
            context, device, allowRemoteAccountSettings = true, refreshRemoteAccountSettings = false
        )
        assertEquals(missingSettings.expectedOptInResult(), deviceSettings.expectedOptInResult())
        assertFalse(deviceSettings.timelineEnabled)
        assertFalse(deviceSettings.allowed)
        assertFalse(fetchEffectiveTimelineEnabled(context, device, allowRemoteAccountSettings = true))

        val ownerSettings = fetchEffectiveAccountLocationSettings(
            context, owner, allowRemoteAccountSettings = false, refreshRemoteAccountSettings = false
        )
        assertTrue(ownerSettings.expectedOptInResult() != missingSettings.expectedOptInResult())
    }

    @Test
    fun blockstoreRetrieveBytesReturnsEmptyArrayWhenNothingStored() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val service = BlobstoreServiceImpl(BlockStoreImpl(context, "com.example.empty.${System.nanoTime()}"), owner.lifecycle)

        val latch = CountDownLatch(1)
        var status: Status? = null
        var bytes: ByteArray? = null
        val callback = object : IRetrieveBytesCallback.Stub() {
            override fun onBytesResult(s: Status?, result: ByteArray?) {
                status = s
                bytes = result
                latch.countDown()
            }

            override fun onResponseResult(s: Status?, response: RetrieveBytesResponse?) = Unit
        }

        service.retrieveBytes(callback)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (latch.count > 0 && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(10)
        }
        assertEquals("Timed out waiting for Blockstore callback", 0L, latch.count)
        assertEquals(Status.SUCCESS.statusCode, status?.statusCode)
        assertNotNull("Stock GMS returns ByteArray(0), never null", bytes)
        assertArrayEquals(ByteArray(0), bytes)
    }
}
