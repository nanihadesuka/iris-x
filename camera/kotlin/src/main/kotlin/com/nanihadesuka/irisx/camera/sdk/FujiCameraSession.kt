package com.nanihadesuka.irisx.camera.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.coroutines.coroutineContext

/**
 * Iris X's own session with the camera, in plain Kotlin, for the
 * operations Iris X uses. Read-only by construction: see [PtpOp].
 */
internal class FujiCameraSession private constructor(
    private val socket: Socket,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val transport = PtpTransport(socket, log)

    /** One operation at a time. The poller waits its turn and skips a beat while the camera is busy with a real one. */
    private val turn = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reserved = AtomicInteger(0)
    private val cancelRequested = AtomicBoolean(false)
    private val reading = AtomicBoolean(false)
    @Volatile private var settled = false

    /** The largest count of reserved photos the camera has reported, once it has. */
    val reservedCount: Int? get() = reserved.get().takeIf { it > 0 }

    // -- Listing --------------------------------------------------------------------------------------------------------

    /**
     * The start-up order: compression off, slot status, search modes, current object, counts by
     * date, then count and handles. The date count comes first because it makes the camera walk its card index.
     */
    suspend fun listCard(): CardListing = operation {
        setProp16(FujiProp.FORCE_COMPRESSION, ImageQuality.Default.forceCompression)
        setProp16(FujiProp.COMPRESSION_REAL_INFO, 0)
        val slot = getProp(FujiProp.DUAL_SLOT_STATUS)
        log("SDK slot status ${slot.joinToString(" ") { "%02x".format(it) }}")
        // Answered and not needed; sent because this order is the only one known to be safe for the card.
        request(PtpOp.GET_SEARCH_MODE_DESC_ALL, LONG_TIMEOUT_MS)
        getProp(FujiProp.CURRENT_OBJECT)
        val byDate = request(PtpOp.GET_OBJECT_COUNT_BY_DATE, LONG_TIMEOUT_MS, intArrayOf(0, MAX_LISTED_OBJECTS))
        val counts = parseCountsByDate(byDate)
        val total = getProp(FujiProp.OBJECT_COUNT, LONG_TIMEOUT_MS).littleEndian().let { if (it.remaining() >= 4) it.int else 0 }
        val handles = parseHandles(getProp(FujiProp.OBJECT_HANDLES, LONG_TIMEOUT_MS))
        log("SDK $total on the card, ${handles.size} handles, ${counts.size} dates")
        CardListing(handles.sortedDescending(), counts)
    }

    /** The object, or null when the camera has none under this handle. */
    suspend fun objectInfo(handle: Int): ObjectInfo? = operation { readInfo(handle) }

    /** The camera's thumbnail JPEG. Takes no size: the camera always answers with the one it has. */
    suspend fun thumbnail(handle: Int): ByteArray = operation {
        request(PtpOp.GET_THUMB, LONG_TIMEOUT_MS, intArrayOf(handle))
    }

    /** Sets what later reads return, and the size [objectInfo] reports. */
    suspend fun setQuality(quality: ImageQuality) = operation { setProp16(FujiProp.FORCE_COMPRESSION, quality.forceCompression) }

    /** Reads the next photo reserved for this phone. The camera advances its own queue, so it is always handle 1. */
    suspend fun nextReserved(): ObjectInfo? = operation {
        poll()
        readInfo(RESERVED_HANDLE)
    }

    // -- Reading --------------------------------------------------------------------------------------------------------

    /**
     * Copies an object to [sink] piece by piece, from [startAt] if the start has already arrived. Polling pauses meanwhile,
     * as it does in the native library. Throws [ReadCancelledException] after [cancelRead]; the session stays usable.
     */
    suspend fun read(handle: Int, sizeBytes: Long, sink: OutputStream, startAt: Long = 0, onProgress: (Long) -> Unit = {}) = operation {
        cancelRequested.set(false)
        reading.set(true)
        try {
            val chunk = chunkSize()
            var offset = startAt
            while (offset < sizeBytes) {
                coroutineContext.ensureActive()
                if (cancelRequested.get()) throw ReadCancelledException()
                val before = offset
                val size = minOf(chunk, sizeBytes - offset)
                val params = partialParams(handle, offset, size)
                val reply = retryBusy {
                    transport.transact(PtpOp.GET_PARTIAL_OBJECT, LONG_TIMEOUT_MS, params, sink = sink) { n ->
                        offset += n
                        onProgress(offset)
                    }
                }
                if (reply.code != PtpResponse.OK) throw PtpException(PtpOp.GET_PARTIAL_OBJECT, reply.code)
                check(offset > before) { "The camera sent nothing at offset $before" }
            }
        } finally {
            reading.set(false)
        }
    }

    /** Stops the read in progress at its next chunk. Does nothing when there is none, so it cannot stop a later read. */
    fun cancelRead() {
        if (reading.get()) cancelRequested.set(true)
    }

    // -- Plumbing ---------------------------------------------------------------------------------------------------------

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) { turn.withLock { block() } }

    private fun chunkSize(): Long {
        val camera = runCatching { getProp(FujiProp.MAX_PARTIAL_SIZE).littleEndian().int.toLong() and 0xFFFFFFFFL }.getOrDefault(CHUNK_BYTES)
        return camera.coerceIn(MIN_CHUNK_BYTES, CHUNK_BYTES)
    }

    private fun readInfo(handle: Int): ObjectInfo? {
        val r = retryBusy { transport.transact(PtpOp.GET_OBJECT_INFO, LONG_TIMEOUT_MS, intArrayOf(handle)) }
        if (r.code != PtpResponse.OK) {
            log("SDK object $handle -> 0x${r.code.toString(16)}")
            return null
        }
        return parseObjectInfo(handle, r.data)
    }

    private fun request(op: Int, timeoutMs: Int, params: IntArray = IntArray(0)): ByteArray {
        val r = retryBusy { transport.transact(op, timeoutMs, params) }
        if (r.code != PtpResponse.OK) throw PtpException(op, r.code)
        return r.data
    }

    private fun getProp(prop: Int, timeoutMs: Int = TIMEOUT_MS): ByteArray =
        request(PtpOp.GET_DEVICE_PROP_VALUE, timeoutMs, intArrayOf(prop))

    private fun setProp(prop: Int, value: ByteArray) {
        require(prop in FujiProp.WRITABLE) { "Property 0x${prop.toString(16)} is not writable here" }
        val r = retryBusy { transport.transact(PtpOp.SET_DEVICE_PROP_VALUE, TIMEOUT_MS, intArrayOf(prop), outData = value) }
        if (r.code != PtpResponse.OK) throw PtpException(PtpOp.SET_DEVICE_PROP_VALUE, r.code)
    }

    private fun setProp16(prop: Int, value: Int) =
        setProp(prop, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())

    private fun setProp32(prop: Int, value: Int) =
        setProp(prop, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())

    /** A busy camera gets five tries, 100 ms apart. */
    private fun retryBusy(call: () -> PtpTransport.Reply): PtpTransport.Reply {
        var reply = call()
        var attempt = 1
        while (reply.code == PtpResponse.DEVICE_BUSY && attempt < BUSY_ATTEMPTS) {
            Thread.sleep(BUSY_DELAY_MS)
            reply = call()
            attempt++
        }
        return reply
    }

    private fun poll() {
        for (change in parseChanges(getProp(FujiProp.CHANGES, SHORT_TIMEOUT_MS))) {
            when (change.prop) {
                FujiProp.SETTLED -> settled = true
                FujiProp.RESERVED_COUNT -> reserved.accumulateAndGet(change.value.toInt(), ::maxOf)
            }
        }
    }

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                delay(POLL_MS)
                if (turn.tryLock()) {
                    try {
                        poll()
                    } catch (e: Exception) {
                        log("SDK poll failed: ${e.message}")
                        return@launch
                    } finally {
                        turn.unlock()
                    }
                }
            }
        }
    }

    private suspend fun setUp(mode: SessionMode) {
        val open = transport.transact(PtpOp.OPEN_SESSION, SHORT_TIMEOUT_MS, intArrayOf(SESSION_ID))
        if (open.code != PtpResponse.OK) throw PtpException(PtpOp.OPEN_SESSION, open.code)
        // Wait for this before setting the function mode. Going on without it only risks a busy answer.
        val waited = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            while (true) {
                delay(POLL_MS)
                poll()
                if (settled) break
            }
        }
        if (waited == null) log("SDK the camera did not report settling; continuing")
        if (mode == SessionMode.ReservedTransfer) readInfo(RESERVED_HANDLE)
        setProp16(FujiProp.FUNCTION_MODE, mode.functionMode)
        // Negotiated: the camera's own version, capped at what this protocol was read from.
        getProp(mode.versionProp)
        val camera = getProp(mode.versionProp).littleEndian().int
        setProp32(mode.versionProp, minOf(camera, APP_FUNCTION_VERSION))
        if (mode == SessionMode.PhotoView) getProp(FujiProp.DUAL_SLOT_STATUS)
        startPolling()
    }

    /** Ends the session politely if the camera is free, then the connection in any case. */
    override fun close() {
        scope.cancel()
        repeat(CLOSE_TRIES) {
            if (turn.tryLock()) {
                runCatching { transport.transact(PtpOp.CLOSE_SESSION, SHORT_TIMEOUT_MS) }.onFailure { log("SDK close: ${it.message}") }
                turn.unlock()
                runCatching { socket.close() }
                return
            }
            Thread.sleep(CLOSE_WAIT_MS)
        }
        log("SDK closed with an operation still running")
        runCatching { socket.close() }
    }

    companion object {
        const val PORT = 55740
        private const val SESSION_ID = 1
        private const val RESERVED_HANDLE = 1
        private const val APP_FUNCTION_VERSION = 3
        private const val MAX_LISTED_OBJECTS = 30_000
        private const val SHORT_TIMEOUT_MS = 5_000
        private const val TIMEOUT_MS = 7_000
        /**
         * The native library forces 30 s on object reads. Listing gets it too: the camera may be walking its index, and
         * giving up and closing mid-walk is the suspect in the one SD card error seen so far.
         */
        private const val LONG_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val CONNECT_RETRY_WAIT_MS = 500L
        private const val BUSY_ATTEMPTS = 5
        private const val BUSY_DELAY_MS = 100L
        private const val POLL_MS = 200L
        private const val SETTLE_TIMEOUT_MS = 30_000L
        private const val CLOSE_TRIES = 20
        private const val CLOSE_WAIT_MS = 100L
        /** Smaller than the camera allows, so cancelling and progress respond within a chunk. The library's own default. */
        private const val CHUNK_BYTES = 0x100000L
        private const val MIN_CHUNK_BYTES = 0xFFE0L
        private const val INIT_REQUEST_BYTES = 82
        private const val INIT_NAME_CHARS = 26
        private const val INIT_FAILED = 5
        private const val PROTOCOL_VERSION = 0x8F53E4F2.toInt()
        private val CLIENT_GUID = intArrayOf(0x5D48A5AD, 0x0B7FB287, 0xD0DED5D3.toInt(), 0)

        /** A read's parameters; the high half of the offset goes last, and only for files over 4 GB. */
        internal fun partialParams(handle: Int, offset: Long, size: Long): IntArray {
            val high = (offset ushr 32).toInt()
            return if (high == 0) intArrayOf(handle, offset.toInt(), size.toInt()) else intArrayOf(handle, offset.toInt(), size.toInt(), high)
        }

        /**
         * One attempt: connects, shakes hands and opens the session. The caller repeats it, and must have
         * joined the camera's Wi-Fi and passed that network's [factory].
         */
        suspend fun open(factory: SocketFactory, host: String, phoneName: String, mode: SessionMode, log: (String) -> Unit, port: Int = PORT): FujiCameraSession =
            withContext(Dispatchers.IO) {
                val socket = factory.createSocket()
                try {
                    try {
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    } catch (e: Exception) {
                        delay(CONNECT_RETRY_WAIT_MS)
                        throw e
                    }
                    socket.soTimeout = SHORT_TIMEOUT_MS
                    handshake(socket, phoneName, log)
                    FujiCameraSession(socket, log).also { it.setUp(mode) }
                } catch (t: Throwable) {
                    runCatching { socket.close() }
                    throw t
                }
            }

        private fun handshake(socket: Socket, phoneName: String, log: (String) -> Unit) {
            val request = ByteBuffer.allocate(INIT_REQUEST_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            request.putInt(INIT_REQUEST_BYTES).putInt(1).putInt(PROTOCOL_VERSION)
            CLIENT_GUID.forEach { request.putInt(it) }
            request.put(phoneName.take(INIT_NAME_CHARS).toByteArray(Charsets.UTF_16LE))
            socket.getOutputStream().write(request.array())
            socket.getOutputStream().flush()
            // Not buffered: the session's own reader takes over right after these bytes.
            val input = DataInputStream(socket.getInputStream())
            val lengthBytes = ByteArray(4).also { input.readFully(it) }
            val length = lengthBytes.littleEndian().int
            check(length in 8..MAX_INIT_ANSWER_BYTES) { "Bad handshake length $length" }
            val answer = ByteArray(length - 4).also { input.readFully(it) }
            val type = answer.littleEndian().int
            check(type != INIT_FAILED) { "The camera refused the connection" }
            log("SDK handshake answered, type $type, ${length} bytes")
        }

        private const val MAX_INIT_ANSWER_BYTES = 4096
    }
}
