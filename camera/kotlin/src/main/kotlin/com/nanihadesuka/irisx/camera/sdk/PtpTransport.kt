package com.nanihadesuka.irisx.camera.sdk

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plain PTP containers over one TCP socket: length u32, type u16, code u16, transaction u32, then parameters or data.
 * After the opening handshake the camera uses no PTP/IP packet types and no event channel. Not thread safe: the session
 * runs one operation at a time, because that is all the protocol allows.
 */
internal class PtpTransport(private val socket: Socket, private val log: (String) -> Unit) {
    private val input = DataInputStream(socket.getInputStream().buffered(BUFFER_BYTES))
    private val output = socket.getOutputStream()
    private var transaction = 0

    class Reply(val code: Int, val data: ByteArray)

    /** Runs one operation. Data the camera sends goes to [sink], or is returned in the reply when there is none. */
    fun transact(
        op: Int,
        timeoutMs: Int,
        params: IntArray = IntArray(0),
        outData: ByteArray? = null,
        sink: OutputStream? = null,
        onBytes: (Int) -> Unit = {},
    ): Reply {
        // The camera is read-only for Iris X: see PtpOp.
        require(op in PtpOp.ALLOWED) { "Operation 0x${op.toString(16)} is not allowed" }
        val tx = ++transaction
        socket.soTimeout = timeoutMs
        send(TYPE_COMMAND, op, tx, params, null)
        if (outData != null) send(TYPE_DATA, op, tx, IntArray(0), outData)
        val received = if (sink == null) ByteArrayOutputStream() else null
        val target = sink ?: received!!
        while (true) {
            val header = ByteArray(HEADER_BYTES).also { input.readFully(it) }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val length = h.int.toLong() and 0xFFFFFFFFL
            val type = h.short.toInt() and 0xFFFF
            val code = h.short.toInt() and 0xFFFF
            val replyTx = h.int
            check(length >= HEADER_BYTES) { "Bad container length $length" }
            check(replyTx == tx) { "Answer for transaction $replyTx while waiting for $tx" }
            var left = length - HEADER_BYTES
            when (type) {
                TYPE_DATA -> copy(left, target, onBytes)
                TYPE_RESPONSE -> {
                    while (left > 0) left -= input.skipBytes(left.toInt()).also { if (it <= 0) throw EOFException("Camera closed") }
                    log("PTP 0x${op.toString(16)} tx=$tx -> 0x${code.toString(16)}")
                    return Reply(code, received?.toByteArray() ?: ByteArray(0))
                }
                else -> error("Unexpected container type $type")
            }
        }
    }

    private fun copy(length: Long, target: OutputStream, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        var left = length
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException("Camera closed during a read")
            target.write(buffer, 0, n)
            left -= n
            onBytes(n)
        }
    }

    private fun send(type: Int, op: Int, tx: Int, params: IntArray, data: ByteArray?) {
        val size = HEADER_BYTES + params.size * 4 + (data?.size ?: 0)
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(size).putShort(type.toShort()).putShort(op.toShort()).putInt(tx)
        params.forEach { b.putInt(it) }
        data?.let { b.put(it) }
        output.write(b.array())
        output.flush()
    }

    private companion object {
        const val HEADER_BYTES = 12
        const val BUFFER_BYTES = 64 * 1024
        const val TYPE_COMMAND = 1
        const val TYPE_DATA = 2
        const val TYPE_RESPONSE = 3
    }
}
