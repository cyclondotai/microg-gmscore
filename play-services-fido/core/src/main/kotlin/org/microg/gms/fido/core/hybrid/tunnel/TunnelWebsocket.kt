/**
 * SPDX-FileCopyrightText: 2025 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.fido.core.hybrid.tunnel

import android.util.Log
import androidx.annotation.VisibleForTesting
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "TunnelWebsocket"

enum class SocketStatus {
    NONE, CONNECTING, CONNECTED, DISCONNECTED
}

interface TunnelWebCallback {
    fun disconnected()
    fun error(error: TunnelException)
    fun connected(response: Response)
    fun message(data: ByteArray)
}

data class TunnelException(val msg: String, val th: Throwable? = null) : RuntimeException(msg, th)

class TunnelWebsocket @VisibleForTesting constructor(
    val url: String,
    val callback: TunnelWebCallback,
    private val webSocketFactory: ((Request, WebSocketListener) -> WebSocket)?,
    private val workerExecutor: ((Runnable) -> Unit)?,
) {
    constructor(url: String, callback: TunnelWebCallback) : this(url, callback, null, null)

    private val threadPool = Executors.defaultThreadFactory()
    private var submitThread: Thread? = null

    @Volatile
    private var socketStatus = SocketStatus.NONE

    @Volatile
    private var socket: WebSocket? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
    }

    @Synchronized
    fun close() {
        Log.d(TAG, "close() with state= $socketStatus")
        val ordinal = socketStatus.ordinal
        if (ordinal == 0) {
            socketStatus = SocketStatus.DISCONNECTED
            return
        }
        closeWebsocket()
    }

    @Synchronized
    fun closeWebsocket() {
        if (socketStatus == SocketStatus.DISCONNECTED) {
            return
        }
        Log.d(TAG, "closeWebsocket: ")
        val previousStatus = this.socketStatus
        val activeSocket = this.socket
        this.socket = null
        this.socketStatus = SocketStatus.DISCONNECTED
        if (activeSocket != null) {
            try {
                if (previousStatus == SocketStatus.CONNECTED) {
                    activeSocket.close(1000, "Done")
                } else {
                    activeSocket.cancel()
                }
            } catch (e: IOException) {
                throw TunnelException("Socket failed to close", e)
            }
        }
        this.callback.disconnected()
    }

    @Synchronized
    fun connect() {
        Log.d(TAG, "connect() with state= $socketStatus")
        if (this.socketStatus != SocketStatus.NONE) {
            Log.d(TAG, "connect() has already been called")
            this.callback.error(TunnelException("connect() has already been called"))
            close()
            return
        }
        val task = Runnable {
            Log.d(TAG, "runReader()")
            try {
                synchronized(this) {
                    if (socketStatus == SocketStatus.DISCONNECTED) {
                        Log.d(TAG, "runReader() skipped because websocket is disconnected")
                        val activeSocket = socket
                        socket = null
                        if (activeSocket != null) {
                            runCatching { activeSocket.cancel() }
                                .onFailure { Log.w(TAG, "connect: Socket failed to close") }
                        }
                    } else {
                        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "fido.cable").build()
                        val listener = object : WebSocketListener() {
                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                closeWebsocket()
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                closeWebsocket()
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                Log.e(TAG, "Tunnel failure")
                                val shouldNotify = synchronized(this@TunnelWebsocket) {
                                    if (socketStatus == SocketStatus.DISCONNECTED) {
                                        false
                                    } else {
                                        socketStatus = SocketStatus.DISCONNECTED
                                        socket = null
                                        true
                                    }
                                }
                                if (shouldNotify) {
                                    callback.error(TunnelException("Websocket failed", t))
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                if (socketStatus == SocketStatus.DISCONNECTED) return
                                Log.d(TAG, "Received ${bytes.size} bytes")
                                callback.message(bytes.toByteArray())
                            }

                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                val opened = synchronized(this@TunnelWebsocket) {
                                    if (socketStatus == SocketStatus.DISCONNECTED) {
                                        false
                                    } else {
                                        socketStatus = SocketStatus.CONNECTED
                                        true
                                    }
                                }
                                if (!opened) {
                                    runCatching { webSocket.close(1000, "Done") }
                                    return
                                }
                                callback.connected(response)
                            }
                        }
                        socket = webSocketFactory?.invoke(request, listener) ?: client.newWebSocket(request, listener)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "connect: ")
                if (socketStatus != SocketStatus.DISCONNECTED) {
                    callback.error(TunnelException("Websocket connect failed", e))
                }
            }
        }
        this.socketStatus = SocketStatus.CONNECTING
        val customExecutor = workerExecutor
        if (customExecutor != null) {
            customExecutor(task)
        } else {
            val threadNewThread = threadPool.newThread(task)
            this.submitThread = threadNewThread
            threadNewThread.setName("TunnelWebSocket")
            this.submitThread?.start()
        }
    }

    // OkHttp WebSocket.send is thread-safe. Never acquire this object's monitor or call
    // callbacks here: a caller may hold the controller output guard, while close has this monitor.
    internal fun enqueue(bArr: ByteArray): Boolean {
        val activeSocket = socket
        if (socketStatus != SocketStatus.CONNECTED || activeSocket == null) return false
        return try { activeSocket.send(ByteString.of(*bArr)) } catch (_: Exception) { false }
    }

    fun send(bArr: ByteArray): Boolean {
        val accepted = enqueue(bArr)
        // The ordinary handshake/client callers retain their failure callback, outside any output guard.
        if (!accepted) callback.error(TunnelException("Websocket rejected frame"))
        return accepted
    }
}
