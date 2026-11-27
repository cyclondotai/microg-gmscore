/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.accounts.Account
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ChromePasswordServiceTest {
    private class FakeVault(
        override val enabled: Boolean = true,
        private val onList: () -> List<PasswordView> = { emptyList() },
        private val onUpsert: (ByteArray) -> Unit = {},
        private val onSetCustomKey: (ByteArray, ByteArray) -> Unit = { _, _ -> },
    ) : PasswordVault {
        var receivedEnc: ByteArray? = null
        var receivedMac: ByteArray? = null
        override fun setEnabled(value: Boolean) = Unit
        override fun refresh(): SyncReceipt = SyncReceipt(0, 0, 0, 0)
        override fun initializeEmptyVault() = Unit
        override fun list(ensureInitialSync: Boolean): List<PasswordView> = onList()
        override fun upsert(chromePassword: ByteArray) = onUpsert(chromePassword)
        override fun upsertAutomatic(chromePassword: ByteArray) = onUpsert(chromePassword)
        override fun delete(chromePassword: ByteArray) = Unit
        override fun setCustomKey(encryption: ByteArray, mac: ByteArray) {
            receivedEnc = encryption.copyOf()
            receivedMac = mac.copyOf()
            onSetCustomKey(encryption, mac)
        }
        override fun unlockPassphrase(password: ByteArray) = Unit
        override fun getPreference(name: String): String? = null
        override fun setPreference(name: String, value: String) = Unit
    }

    private class TestChromePasswordService : ChromePasswordService() {
        lateinit var vault: PasswordVault
        override fun openVault(account: Account?): PasswordVault = vault
    }

    private fun startService(vault: PasswordVault): Pair<TestChromePasswordService, Binder> {
        val controller = Robolectric.buildService(TestChromePasswordService::class.java).create()
        val service = controller.get().apply { this.vault = vault }
        val uid = 10234
        ShadowBinder.setCallingUid(uid)
        val binder = service.createBinder(uid, Account("owner@example.com", "com.google"))
        return service to binder
    }

    private fun captureStatus(descriptor: String): Pair<Binder, () -> Status> {
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Status>()
        val cb = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(descriptor)
                if (data.readInt() != 0) ref.set(Status.CREATOR.createFromParcel(data))
                latch.countDown()
                return true
            }
        }
        return cb to {
            assertTrue("timed out waiting for callback", latch.await(5, TimeUnit.SECONDS))
            ref.get()!!
        }
    }

    private fun transactList(binder: Binder): Status {
        val (cb, await) = captureStatus("com.google.android.gms.chromesync.firstparty.internal.IListPasswordsResultCallback")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ChromePasswordService.DESCRIPTOR)
            data.writeStrongBinder(cb)
            data.writeInt(1)
            val header = SafeParcelWriter.writeObjectHeader(data)
            SafeParcelWriter.finishObjectHeader(data, header)
            data.writeInt(0) // nullable ApiMetadata
            data.setDataPosition(0)
            assertTrue(binder.transact(2, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        return await()
    }

    private fun transactAffiliated(binder: Binder, facet: String): Status {
        val (cb, await) = captureStatus("com.google.android.gms.chromesync.common.internal.IDataHolderResultCallback")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ChromePasswordService.DESCRIPTOR)
            data.writeStrongBinder(cb)
            data.writeInt(1)
            val header = SafeParcelWriter.writeObjectHeader(data)
            SafeParcelWriter.write(data, 1, facet, false)
            SafeParcelWriter.finishObjectHeader(data, header)
            data.writeInt(0) // nullable ApiMetadata
            data.setDataPosition(0)
            assertTrue(binder.transact(7, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        return await()
    }

    private fun transactListUiInfo(binder: Binder): Status {
        val (cb, await) = captureStatus("com.google.android.gms.chromesync.common.internal.IDataHolderResultCallback")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ChromePasswordService.DESCRIPTOR)
            data.writeStrongBinder(cb)
            data.writeInt(0) // nullable ApiMetadata
            data.setDataPosition(0)
            assertTrue(binder.transact(8, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        return await()
    }

    private fun transactUpsert(binder: Binder, entity: ByteArray): Status {
        val (cb, await) = captureStatus("com.google.android.gms.common.api.internal.IStatusCallback")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ChromePasswordService.DESCRIPTOR)
            data.writeStrongBinder(cb)
            data.writeInt(1)
            Bundle().apply { putByteArray("entity", entity) }.writeToParcel(data, 0)
            data.writeInt(0) // nullable ApiMetadata
            data.setDataPosition(0)
            assertTrue(binder.transact(3, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        return await()
    }

    private fun transactSetCustomKey(binder: Binder, enc: ByteArray, mac: ByteArray): Status {
        val (cb, await) = captureStatus("com.google.android.gms.common.api.internal.IStatusCallback")
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ChromePasswordService.DESCRIPTOR)
            data.writeStrongBinder(cb)
            data.writeByteArray(enc)
            data.writeByteArray(mac)
            data.writeInt(0) // nullable ApiMetadata
            data.setDataPosition(0)
            assertTrue(binder.transact(9, data, reply, 0))
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
        return await()
    }

    @Test fun enabledCustomPassphraseVaultReturns11000WhileDisabledVaultReturns4() {
        ShadowLog.clear()
        val (_, enabledBinder) = startService(FakeVault(enabled = true, onList = { throw PassphraseRequiredException() }, onUpsert = { throw PassphraseRequiredException() }))
        assertEquals(ChromePasswordService.STATUS_PASSPHRASE_REQUIRED, transactList(enabledBinder).statusCode)
        assertEquals(ChromePasswordService.STATUS_PASSPHRASE_REQUIRED, transactUpsert(enabledBinder, byteArrayOf(1, 2, 3)).statusCode)
        assertEquals(ChromePasswordService.STATUS_PASSPHRASE_REQUIRED, transactAffiliated(enabledBinder, "https://example.com").statusCode)
        assertEquals(ChromePasswordService.STATUS_PASSPHRASE_REQUIRED, transactListUiInfo(enabledBinder).statusCode)

        val (_, disabledBinder) = startService(FakeVault(enabled = false, onList = { throw VaultLockedException() }))
        assertEquals(4, transactList(disabledBinder).statusCode)

        val logs = ShadowLog.getLogsForTag("ChromePasswordService").map { it.msg }
        assertTrue("logs code=2 and 11000 status", logs.any { it.contains("code=2") && it.contains("status=11000") })
        assertTrue("logs code=3 and 11000 status", logs.any { it.contains("code=3") && it.contains("status=11000") })
        assertTrue("logs code=7 and 11000 status", logs.any { it.contains("code=7") && it.contains("status=11000") })
        assertTrue("logs code=8 and 11000 status", logs.any { it.contains("code=8") && it.contains("status=11000") })
        assertTrue("logs code=2 and status=4 for disabled vault", logs.any { it.contains("code=2") && it.contains("status=4") })
    }

    @Test fun setCustomKeySucceedsAndNeverReturns11000OrLogsKeyMaterialOnError() {
        ShadowLog.clear()
        val enc = ByteArray(16) { (0x41 + it).toByte() }
        val mac = ByteArray(16) { (0x61 + it).toByte() }
        val vault = FakeVault(enabled = true)
        val (_, binder) = startService(vault)
        assertEquals(Status.SUCCESS.statusCode, transactSetCustomKey(binder, enc, mac).statusCode)
        assertArrayEquals(enc, vault.receivedEnc)
        assertArrayEquals(mac, vault.receivedMac)

        val (_, rejectingBinder) = startService(FakeVault(enabled = true, onSetCustomKey = { _, _ -> throw PassphraseRequiredException() }))
        assertEquals("code 9 returns 4 (not 11000) when key does not match", 4, transactSetCustomKey(rejectingBinder, enc, mac).statusCode)

        val logs = ShadowLog.getLogsForTag("ChromePasswordService").map { it.msg }
        assertTrue(logs.any { it.contains("code=9") && it.contains("status=4") })
        val encText = String(enc, Charsets.UTF_8)
        val macText = String(mac, Charsets.UTF_8)
        assertTrue("never logs key material", logs.none { it.contains(encText) || it.contains(macText) })
    }
}
