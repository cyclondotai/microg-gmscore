/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.fido.core.hybrid.controller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import com.google.android.gms.fido.fido2.api.common.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.microg.gms.fido.core.RequestHandlingException
import org.microg.gms.fido.core.hybrid.HandshakePhase
import org.microg.gms.fido.core.hybrid.ble.HybridClientScan
import org.microg.gms.fido.core.hybrid.generateEcKeyPair
import org.microg.gms.fido.core.hybrid.transport.ClientTunnelTransport
import org.microg.gms.fido.core.hybrid.transport.TunnelCallback
import org.microg.gms.fido.core.hybrid.tryResumeData
import org.microg.gms.fido.core.hybrid.tryResumeWithError
import org.microg.gms.fido.core.hybrid.tunnel.TunnelException
import org.microg.gms.fido.core.hybrid.tunnel.TunnelWebsocket
import org.microg.gms.fido.core.hybrid.utils.CryptoHelper
import org.microg.gms.fido.core.hybrid.utils.NoiseCrypter
import org.microg.gms.fido.core.hybrid.utils.NoiseHandshakeState
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "HybridClientController"

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class HybridClientController(context: Context, val staticKey: Pair<ECPublicKey, ECPrivateKey>) {
    private val appContext = context.applicationContext
    private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var ephemeralKeyPair: Pair<ECPublicKey, ECPrivateKey>? = null
    private var noise: NoiseHandshakeState? = null
    private var crypter: NoiseCrypter? = null

    private var phase = HandshakePhase.NONE
    private var scan: HybridClientScan? = null
    private var transport: ClientTunnelTransport? = null

    private var postHandshakeDone = false
    private val firstSend = AtomicBoolean(false)

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun release() {
        runCatching { scan?.stopScanning() }.onFailure {
            Log.w(TAG, "release: stopScanning failed")
        }
        scan = null

        runCatching { transport?.stopConnecting() }.onFailure {
            Log.w(TAG, "release: websocket close failed")
        }
        transport = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    suspend fun startClientTunnel(
        eid: ByteArray, seed: ByteArray, frameBuilder: () -> ByteArray?
    ) = suspendCancellableCoroutine { cont ->
        transport = ClientTunnelTransport(eid, seed, object : TunnelCallback {
            override fun onSocketConnect(websocket: TunnelWebsocket?, bytes: ByteArray) {
                runCatching { sendNoiseHello(websocket, bytes) }.onFailure { if (!cont.isCompleted) cont.tryResumeWithError(it) }
            }

            override fun onSocketError(error: TunnelException) {
                if (!cont.isCompleted) cont.tryResumeWithError(RequestHandlingException(ErrorCode.UNKNOWN_ERR, error.message ?: "error"))
            }

            override fun onSocketClose() {
                if (!cont.isCompleted) cont.tryResumeWithError(RequestHandlingException(ErrorCode.UNKNOWN_ERR, "Tunnel closed"))
            }

            override fun onMessage(websocket: TunnelWebsocket?, data: ByteArray) {
                runCatching {
                    when (phase) {
                        HandshakePhase.CLIENT_HELLO_SENT -> if (data.size == 81) handleServerHello(websocket, data, frameBuilder())
                        else error("Unexpected handshake payload size")

                        HandshakePhase.READY -> handlePostHandshake(websocket, data, frameBuilder())?.also { if (!cont.isCompleted) cont.tryResumeData(it) }

                        else -> error("Data received in invalid phase=$phase")
                    }
                }.onFailure {
                    if (!cont.isCompleted) cont.tryResumeWithError(it)
                }
            }
        })

        transport!!.startConnecting()

        cont.invokeOnCancellation { runCatching { transport?.stopConnecting() } }
    }

    private fun sendNoiseHello(ws: TunnelWebsocket?, socketHash: ByteArray) {
        val nh = NoiseHandshakeState(mode = 3).also { noise = it }
        nh.mixHash(byteArrayOf(1))
        nh.mixHash(CryptoHelper.uncompress(staticKey.first))
        nh.mixKeyAndHash(socketHash)

        ephemeralKeyPair = generateEcKeyPair()
        val ephPub = CryptoHelper.uncompress(ephemeralKeyPair!!.first)

        nh.mixHash(ephPub)
        nh.mixKey(ephPub)

        val ciphertext = nh.encryptAndHash(ByteArray(0))
        ws?.send(ephPub + ciphertext)

        phase = HandshakePhase.CLIENT_HELLO_SENT
        Log.d(TAG, ">> ClientHello sent")
    }

    private fun handleServerHello(ws: TunnelWebsocket?, msg: ByteArray, rawFrame: ByteArray?) {
        val frame = rawFrame ?: error("Frame null")
        val nh = noise ?: error("Noise state null")
        val eph = ephemeralKeyPair ?: error("Ephemeral missing")

        val serverPub = msg.sliceArray(0..64)

        nh.mixHash(serverPub)
        nh.mixKey(serverPub)
        nh.mixKey(CryptoHelper.recd(eph.second, serverPub))
        nh.mixKey(CryptoHelper.recd(staticKey.second, serverPub))

        val (send, recv) = nh.splitSessionKeys()
        crypter = NoiseCrypter(recv, send)

        phase = HandshakePhase.READY
        Log.d(TAG, "✓ Handshake done")

        trySendEncrypted(ws, frame)
    }

    private fun handlePostHandshake(ws: TunnelWebsocket?, raw: ByteArray, plainFrame: ByteArray?): ByteArray? {
        val plain = crypter?.decrypt(raw) ?: error("Decrypt failed")
        require(plain.isNotEmpty()) { "Decrypted empty" }

        val type = plain[0].toInt() and 0xFF
        val payload = plain.copyOfRange(1, plain.size)

        return when (type) {
            0x01 -> handleSuccessFrame(payload)
            in 0xA0..0xBF -> handlePostHandshakeFrame(ws, plainFrame)
            else -> error("Unexpected frame: 0x${type.toString(16)}")
        }
    }

    private fun handleSuccessFrame(b: ByteArray): ByteArray {
        val first = b.firstOrNull()?.toInt() ?: return b
        return if (first in listOf(0x01, 0x02)) b.drop(1).toByteArray() else b
    }

    private fun handlePostHandshakeFrame(ws: TunnelWebsocket?, frame: ByteArray?): ByteArray? {
        if (!postHandshakeDone) {
            postHandshakeDone = true
            trySendEncrypted(ws, frame ?: error("Frame null"))
        }
        return null
    }

    private fun trySendEncrypted(ws: TunnelWebsocket?, frame: ByteArray) {
        if (firstSend.compareAndSet(false, true)) {
            ws?.send(crypter?.encrypt(frame) ?: error("Encrypt failed"))
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ensureBluetoothReady(): BluetoothAdapter {
        val btAdapter = adapter
            ?: throw RequestHandlingException(ErrorCode.NOT_SUPPORTED_ERR, "BluetoothAdapter null")
        if (!btAdapter.isEnabled) {
            val turnedOn = awaitBluetoothStateOn(btAdapter)
            if (!turnedOn) {
                throw RequestHandlingException(ErrorCode.NOT_SUPPORTED_ERR, "Bluetooth is off")
            }
        }
        // bluetoothLeScanner can become non-null a moment after STATE_ON is broadcast.
        val deadlineMs = System.currentTimeMillis() + SCANNER_READY_TIMEOUT_MS
        while (btAdapter.bluetoothLeScanner == null && System.currentTimeMillis() < deadlineMs) {
            delay(50)
        }
        if (btAdapter.bluetoothLeScanner == null) {
            throw RequestHandlingException(ErrorCode.NOT_SUPPORTED_ERR, "BluetoothLeScanner null")
        }
        return btAdapter
    }

    @VisibleForTesting
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun awaitBluetoothStateOn(
        btAdapter: BluetoothAdapter,
        enableAdapter: () -> Boolean = { btAdapter.enable() },
    ): Boolean {
        return withTimeoutOrNull(BLUETOOTH_ENABLE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var sawTurningOn = btAdapter.state == BluetoothAdapter.STATE_TURNING_ON
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val previousState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
                        when (state) {
                            BluetoothAdapter.STATE_ON -> {
                                runCatching { appContext.unregisterReceiver(this) }
                                if (cont.isActive) cont.resumeWith(Result.success(true))
                            }
                            BluetoothAdapter.STATE_TURNING_ON -> {
                                sawTurningOn = true
                            }
                            BluetoothAdapter.STATE_OFF -> {
                                // Only abort when an enable attempt itself fell back to OFF. When the adapter was
                                // turning off as enable() was queued, Android broadcasts an intermediate STATE_OFF
                                // (previousState == STATE_TURNING_OFF) before transitioning to STATE_TURNING_ON -> STATE_ON.
                                if (previousState == BluetoothAdapter.STATE_TURNING_ON ||
                                    (sawTurningOn && previousState != BluetoothAdapter.STATE_TURNING_OFF)
                                ) {
                                    runCatching { appContext.unregisterReceiver(this) }
                                    if (cont.isActive) cont.resumeWith(Result.success(false))
                                }
                            }
                        }
                    }
                }
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(receiver, filter)
                }
                cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

                if (btAdapter.isEnabled || btAdapter.state == BluetoothAdapter.STATE_ON) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                    return@suspendCancellableCoroutine
                }
                val state = btAdapter.state
                if (state == BluetoothAdapter.STATE_TURNING_ON) {
                    sawTurningOn = true
                    return@suspendCancellableCoroutine
                }
                if (!enableAdapter()) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resumeWith(Result.success(btAdapter.isEnabled))
                }
            }
        } == true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    suspend fun startBluetoothScan(seed: ByteArray): ByteArray {
        val readyAdapter = ensureBluetoothReady()
        return suspendCancellableCoroutine { cont ->
            scan = HybridClientScan(readyAdapter, seed, onScanSuccess = { eid ->
                cont.tryResumeData(eid)
            }, onScanFailed = { cont.tryResumeWithError(it) })
            scan!!.startScanning()
            cont.invokeOnCancellation { runCatching { scan?.stopScanning() } }
        }
    }

    companion object {
        private const val BLUETOOTH_ENABLE_TIMEOUT_MS = 5_000L
        private const val SCANNER_READY_TIMEOUT_MS = 1_500L
    }
}
