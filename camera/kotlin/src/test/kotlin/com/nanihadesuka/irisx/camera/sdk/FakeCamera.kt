package com.nanihadesuka.irisx.camera.sdk

import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

internal fun hex(text: String): ByteArray =
    text.split(' ', '\n').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

/** Answers like the X100VI does, from captured bytes where there are any. It records what it is sent. */
internal class FakeCamera(
    private val file: ByteArray = ByteArray(0),
    private val busyFirst: Int = 0,
    private val handles: List<Int> = listOf(11, 12, 13),
) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val operations = CopyOnWriteArrayList<Int>()
    val readRequests = CopyOnWriteArrayList<List<Int>>()
    val writes = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    var initRequest: ByteArray = ByteArray(0)
    @Volatile var maxPartial = file.size.coerceAtLeast(1)
    private var busyLeft = busyFirst

    init {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()
                    initRequest = ByteArray(82).also { input.readFully(it) }
                    output.write(INIT_ACK)
                    while (true) {
                        val header = ByteArray(12).also { input.readFully(it) }
                        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val length = h.int
                        h.short
                        val op = h.short.toInt() and 0xFFFF
                        val tx = h.int
                        val body = ByteArray(length - 12).also { input.readFully(it) }
                        val params = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN).let { b -> List(body.size / 4) { b.int } }
                        operations += op
                        if (op == PtpOp.SET_DEVICE_PROP_VALUE) {
                            val dataHeader = ByteArray(12).also { input.readFully(it) }
                            val dataLength = ByteBuffer.wrap(dataHeader).order(ByteOrder.LITTLE_ENDIAN).int
                            writes += params[0] to ByteArray(dataLength - 12).also { input.readFully(it) }
                        }
                        if (op == PtpOp.GET_OBJECT_INFO && busyLeft > 0) {
                            busyLeft--
                            respond(output, op, tx, null, PtpResponse.DEVICE_BUSY)
                            continue
                        }
                        respond(output, op, tx, answer(op, params))
                        if (op == PtpOp.CLOSE_SESSION) return@use
                    }
                }
            }
        }
    }

    private fun answer(op: Int, params: List<Int>): ByteArray? = when (op) {
        PtpOp.GET_OBJECT_INFO -> OBJECT_INFO
        PtpOp.GET_THUMB -> byteArrayOf(1, 2, 3)
        PtpOp.GET_SEARCH_MODE_DESC_ALL -> ByteArray(4)
        PtpOp.GET_OBJECT_COUNT_BY_DATE -> DATE_COUNTS
        PtpOp.GET_PARTIAL_OBJECT -> {
            readRequests += params
            file.copyOfRange(params[1], minOf(file.size.toLong(), params[1].toLong() + params[2]).toInt())
        }
        PtpOp.GET_DEVICE_PROP_VALUE -> when (params[0]) {
            FujiProp.CHANGES -> CHANGES
            FujiProp.MAX_PARTIAL_SIZE -> int32(maxPartial)
            FujiProp.OBJECT_COUNT -> int32(handles.size)
            FujiProp.OBJECT_HANDLES -> ByteBuffer.allocate(4 + handles.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(handles.size); handles.forEach { putInt(it) } }.array()
            FujiProp.VERSION_PHOTO_VIEW, FujiProp.VERSION_RESERVED_TRANSFER -> int32(3)
            else -> int32(0)
        }
        else -> null
    }

    private fun respond(output: java.io.OutputStream, op: Int, tx: Int, data: ByteArray?, code: Int = PtpResponse.OK) {
        if (data != null) output.write(container(2, op, tx, data))
        output.write(container(3, code, tx, ByteArray(0)))
        output.flush()
    }

    private fun container(type: Int, code: Int, tx: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(12 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(12 + data.size).putShort(type.toShort()).putShort(code.toShort()).putInt(tx).put(data).array()

    private fun int32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    override fun close() = server.close()

    companion object {
        /** Captured: Init_Command_Ack from the X100VI. */
        val INIT_ACK = hex(
            """44 00 00 00 02 00 00 00 00 00 00 00 08 70 B0 61 0A 8B 45 93 B2 E7 93 57 DD 36 E0 50 58 00 31 00
               30 00 30 00 56 00 49 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
               00 00 00 00""",
        )

        /** Captured payload of the D212 poll: settled = 0x12, reserved count = 1. */
        val CHANGES = hex("04 00 00 DF 12 00 00 00 20 D2 01 00 00 00 41 DF 01 00 00 00 2F D2 01 00 00 00")

        /** Captured payload of GetObjectInfo(1). */
        val OBJECT_INFO = hex(
            """01 00 00 10 01 38 00 00 A1 61 1E 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0F 00 00 70 08
               00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 0D 44 00 53 00 43 00 46 00 30 00 33
               00 39 00 35 00 2E 00 4A 00 50 00 47 00 00 00 10 32 00 30 00 32 00 36 00 31 00 30 00 33 00 30 00
               54 00 31 00 39 00 32 00 38 00 31 00 30 00 00 00 00 0E 4F 00 72 00 69 00 65 00 6E 00 74 00 61 00
               74 00 69 00 6F 00 6E 00 3A 00 31 00 00 00""",
        )

        /** Not yet captured. Two dates, the first with 5 photos. */
        val DATE_COUNTS: ByteArray = ByteBuffer.allocate(4 + 2 * (4 + 1 + 18 + 4)).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(2)
            for ((date, photos) in listOf("20261030" to 5, "20261031" to 7)) {
                putInt(0)
                put(9)
                date.take(8).forEach { putChar16(it) }
                putShort(0)
                putInt(photos)
            }
        }.array()

        private fun ByteBuffer.putChar16(c: Char) { putShort(c.code.toShort()) }
    }
}
