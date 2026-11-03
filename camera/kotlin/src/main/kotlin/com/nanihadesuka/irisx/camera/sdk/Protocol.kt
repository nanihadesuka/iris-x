package com.nanihadesuka.irisx.camera.sdk

/**
 * The numbers of the camera's protocol.
 *
 * The sets below are also a safety rail: [PtpTransport] refuses anything not listed. The camera is read-only for Iris X,
 * so no delete, format or capture operation can be sent from this SDK, by mistake or otherwise.
 */
internal object PtpOp {
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_OBJECT_INFO = 0x1008
    const val GET_THUMB = 0x100A
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
    const val GET_PARTIAL_OBJECT = 0x101B
    const val GET_SEARCH_MODE_DESC_ALL = 0x9050
    const val GET_OBJECT_COUNT_BY_DATE = 0x9053

    val ALLOWED = setOf(
        OPEN_SESSION, CLOSE_SESSION, GET_OBJECT_INFO, GET_THUMB, GET_DEVICE_PROP_VALUE,
        SET_DEVICE_PROP_VALUE, GET_PARTIAL_OBJECT, GET_SEARCH_MODE_DESC_ALL, GET_OBJECT_COUNT_BY_DATE,
    )
}

internal object PtpResponse {
    const val OK = 0x2001
    const val DEVICE_BUSY = 0x2019
}

internal object FujiProp {
    /** What changed on the camera since the last poll. */
    const val CHANGES = 0xD212
    const val FORCE_COMPRESSION = 0xD226
    const val COMPRESSION_REAL_INFO = 0xD227
    const val CURRENT_OBJECT = 0xD22B
    const val MAX_PARTIAL_SIZE = 0xD235
    const val DUAL_SLOT_STATUS = 0xD244
    const val OBJECT_COUNT = 0xD620
    const val OBJECT_HANDLES = 0xD621
    /** Reported in [CHANGES] when the camera has settled after the session opened. */
    const val SETTLED = 0xDF00
    const val FUNCTION_MODE = 0xDF01
    const val VERSION_PHOTO_VIEW = 0xDF28
    const val VERSION_RESERVED_TRANSFER = 0xDF29
    /** Reported in [CHANGES]: how many photos the camera has reserved for this phone. */
    const val RESERVED_COUNT = 0xDF41

    val WRITABLE = setOf(FORCE_COMPRESSION, COMPRESSION_REAL_INFO, FUNCTION_MODE, VERSION_PHOTO_VIEW, VERSION_RESERVED_TRANSFER)
}

/** Why a session is open. The camera behaves differently in each. */
internal enum class SessionMode(val functionMode: Int, val versionProp: Int) {
    /** The user browses the card. */
    PhotoView(20, FujiProp.VERSION_PHOTO_VIEW),

    /** The camera offers new photos by itself; every photo is object handle 1. */
    ReservedTransfer(21, FujiProp.VERSION_RESERVED_TRANSFER),
}

/** What reads return. */
internal enum class ImageQuality(val forceCompression: Int) {
    /** The camera's small preview, when one exists. */
    Default(0),
    Original(2),
}

/** A PTP operation answered with something other than OK. */
internal class PtpException(val op: Int, val code: Int) : Exception("PTP 0x${op.toString(16)} answered 0x${code.toString(16)}")

/** A read stopped because [FujiCameraSession.cancelRead] asked; the session is still usable. */
internal class ReadCancelledException : Exception("Read cancelled")
