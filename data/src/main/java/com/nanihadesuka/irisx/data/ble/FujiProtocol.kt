package com.nanihadesuka.irisx.data.ble

import com.nanihadesuka.irisx.domain.model.LocationFix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Fujifilm "secure" BLE protocol constants and packet codecs.
 * Reverse-engineered by the furble project (github.com/gkoh/furble); verified-on-device status
 * for the X100VI is tracked in PLAN.md.
 */
internal object FujiProtocol {
    const val COMPANY_ID = 0x04D8

    private fun u(s: String): UUID = UUID.fromString(s)

    // Advertised while bonded / ready to connect
    val ADVERTISED_SERVICE = u("a9d2b304-e8d6-4902-8336-352b772d7597")

    // Pairing
    val PAIR_SERVICE = u("123d8f06-62a1-4935-9322-833c531ee225")
    val STATUS_CHAR = u("f557d96b-8284-4667-8793-b971c1deca2a")
    val IDENT_CHAR = u("85b9163e-62d1-49ff-a6f5-054b4630d4a1")

    // Configuration service
    val CONF_SERVICE = u("4c0020fe-f3b6-40de-acc9-77d129067b14")
    val IND1 = u("a68e3f66-0fcc-4395-8d4c-aa980b5877fa")
    val IND2 = u("bd17ba04-b76b-4892-a545-b73ba1f74dae")
    val NOT1 = u("f9150137-5d40-4801-a8dc-f7fc5b01da50")
    val GEOTAG_REQUEST = u("ad06c7b7-f41a-46f4-a29a-712055319122")
    val NOT6 = u("e6692c5c-b7cd-44f4-95fc-eda07ce32560")

    // "Other notifications" service
    val NOTX_SERVICE = u("4e941240-d01d-46b9-a5ea-67636806830b")
    val NOT4 = u("bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4")
    val NOT5 = u("75823784-fbb7-4b71-abae-cd9a34072e3c")
    val NOT7 = u("aab609c4-94dd-4d89-bc60-665d5090b828")
    val NOT8 = u("2a125640-706d-4dd1-b420-c0f4ab93c361")
    val NOT9 = u("82a9f452-c5ce-4ef5-8203-3fc9a47f8171")
    val NOT10 = u("deef7187-3f43-4364-9e22-11a8c8a15951")
    val GEOTAG_SYNC_INTERVAL = u("c95d91ae-b247-4d6d-8661-7dd5d6a0f85b")

    // A68E3F66 / BD17BA04 are the camera's
    // AP_STATE and TRANSFER_STATE indications; BF6DC9CF is the Wi-Fi SSID string.
    val AP_STATE = IND1
    val TRANSFER_STATE = IND2
    val SSID_CHAR = NOT4
    val PASSPHRASE_CHAR = u("e809256a-915c-4967-92e8-53b7d4cad213")

    // Camera control: ask the camera to start its Wi-Fi access point (FunctionLaunchRequest)
    val CONTROL_SERVICE = u("6514eb81-4e8f-458d-aa2a-e691336cdfac")
    val FUNCTION_LAUNCH_REQUEST = u("600655e6-3637-42f1-8fb2-44efc5c63b13")

    // "This phone wants images" flag (service = PAIR_SERVICE)
    val IMAGE_RECEIVE_STATE = u("a80be3f8-8bcb-4add-a725-170b7a53adc9")

    /** Written when browsing photos on the camera. */
    const val FUNCTION_IN_CAMERA_VIEWING: Short = 3
    const val FUNCTION_IMPORT_IMAGE: Short = 4 // libfuji: 4 = wireless communication (remote shooting); 1 = import image

    // AP_STATE values (little-endian u16)
    const val AP_NOT_LAUNCHED = 0x8000
    const val AP_LAUNCHED = 0x8001
    const val AP_LAUNCHING = 0x8002
    const val AP_RESERVED_TRANSFER = 0x8003
    const val TRANSFERABLE = 0x8001

    // Time sync: the camera notifies DATE_SYNC (NOT1) and expects the phone time written back
    // (from libfuji: "When receive notification from CHR_NOT1_UUID, app writes timestamp").
    val TIME_SERVICE = u("e872b11f-d526-4ae1-9bb4-89a99d48fa59")
    val TIME_CHAR = u("b9bfd37f-ccad-4d36-a1ee-018e792b3edf")

    /** 7-byte little-endian local time: u16 year, month, day, hour, minute, second. */
    fun encodeTime(millis: Long): ByteArray {
        val t = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        return ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(t.year.toShort()).put(t.monthValue.toByte()).put(t.dayOfMonth.toByte())
            .put(t.hour.toByte()).put(t.minute.toByte()).put(t.second.toByte()).array()
    }

    // Startup records
    val CONNECTED_APP_INFO = u("8b5ecf55-fc6b-40d0-b4c1-76f64e5453c7")      // service = PAIR_SERVICE
    val IMAGE_TRANSFER_SETTING = u("caedb497-83bf-482c-91ef-91cf6f1216ff")  // service = NOTX_SERVICE
    val IMAGE_TRANSFER_SETTING_EX = u("98934b2c-756c-4632-aa2f-dcba1bfec824") // service = NOTX_SERVICE

    /** application = 0x80, protocol version 0x0101 little-endian. */
    val CONNECTED_APP_INFO_VALUE = byteArrayOf(0x80.toByte(), 0x01, 0x01)

    // UTC time + timezone record written after every connect (c52edbce, service e872b11f).
    val UTC_TZ_CHAR = u("c52edbce-1fe2-4ecc-9483-907e6592be9e")

    /** year u16, month, day, hour, minute, second (all UTC), offset int32 as +HHMM (e.g. +01:00 -> 100), summertime u8. */
    fun encodeUtcAndTimezone(millis: Long): ByteArray {
        val utc = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC)
        val offsetSeconds = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochMilli(millis)).totalSeconds
        val sign = if (offsetSeconds < 0) -1 else 1
        val abs = kotlin.math.abs(offsetSeconds) / 60
        val hhmm = sign * ((abs / 60) * 100 + abs % 60)
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(utc.year.toShort()).put(utc.monthValue.toByte()).put(utc.dayOfMonth.toByte())
            .put(utc.hour.toByte()).put(utc.minute.toByte()).put(utc.second.toByte())
            .putInt(hhmm).put(0).array()
    }

    /**
     * Name the camera knows this phone by: "<model-with-dashes>-<4 digits>", ASCII, kept for this install so the camera
     * always sees the same name. The length stays within the 17 characters already seen to work.
     */
    fun clientName(context: android.content.Context): String {
        val prefs = context.getSharedPreferences("camera_client", android.content.Context.MODE_PRIVATE)
        prefs.getString("name", null)?.let { return it }
        val model = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(12).trim('-').ifEmpty { "Android" }
        val name = "$model-${(1000..9999).random()}"
        prefs.edit().putString("name", name).apply()
        return name
    }

    /** Filters for a non-pairing (re-connect) scan. */
    val RECONNECT_SERVICES = listOf(
        u("AF854C2E-B214-458E-97E2-912C4ECF2CB8".lowercase()), // SERVICE_FF_FILE_TRANSFER
        u("123D8F06-62A1-4935-9322-833C531EE225".lowercase()), // SERVICE_FF_CONNECTED_DEVICE_INFORMATION_RED
        u("731893F9-744E-4899-B7E3-174106FF2B82".lowercase()), // SERVICE_FF_CAMERA_STARTUP_INFORMATION
        u("804DAA8E-FFEB-4AB3-8E75-6EDD7303208D".lowercase()), // SERVICE_FF_CAMERA_STARTUP_INFORMATION_RED
    )

    // Geotag write
    val GEOTAG_SERVICE = u("3b46ec2b-48ba-41fd-b1b8-ed860b60d22b")
    val GEOTAG_CHAR = u("0f36ec14-29e5-411a-a1b6-64ee8383f090")

    data class Sub(val service: UUID, val characteristic: UUID, val indicate: Boolean, val mandatory: Boolean = false)

    /** Subscriptions in the order the camera expects. */
    val SUBSCRIPTIONS = listOf(
        Sub(CONF_SERVICE, IND1, indicate = true),
        Sub(CONF_SERVICE, IND2, indicate = true),
        Sub(CONF_SERVICE, NOT1, indicate = false),
        Sub(CONF_SERVICE, GEOTAG_REQUEST, indicate = false),
        Sub(NOTX_SERVICE, NOT4, indicate = false),
        Sub(NOTX_SERVICE, NOT5, indicate = false),
        Sub(CONF_SERVICE, NOT6, indicate = false),
        Sub(NOTX_SERVICE, NOT7, indicate = false),
        Sub(NOTX_SERVICE, NOT8, indicate = false),
        Sub(NOTX_SERVICE, NOT9, indicate = false),
        Sub(NOTX_SERVICE, NOT10, indicate = false),
        Sub(NOTX_SERVICE, GEOTAG_SYNC_INTERVAL, indicate = false, mandatory = true),
    )

    /** Characteristics whose notifications may mean "new image written". */
    val NEW_PHOTO_CANDIDATES = setOf(NOT1, NOT4, NOT5, NOT7, NOT8, NOT9, NOT10)

    /** Seconds between location refresh requests from the camera. */
    const val GEOTAG_INTERVAL_SECONDS: Short = 480

    fun u16(value: ByteArray): Int? =
        if (value.size == 2) (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8) else null

    fun u16Bytes(value: Short): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()

    /** Status ack: copy the first three bytes, last byte 0x20. */
    fun statusAck(status: ByteArray): ByteArray = byteArrayOf(status[0], status[1], status[2], 0x20)

    fun geotagIntervalBytes(): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(GEOTAG_INTERVAL_SECONDS).array()

    /** 23-byte little-endian packet: lat/lon (deg*1e7), altitude (m), 4 pad, UTC time. */
    fun encodeGeotag(fix: LocationFix): ByteArray {
        val t = Instant.ofEpochMilli(fix.timeMillis).atOffset(ZoneOffset.UTC)
        return ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((fix.latitude * 10_000_000).toInt())
            .putInt((fix.longitude * 10_000_000).toInt())
            .putInt(fix.altitudeMeters.toInt())
            .put(ByteArray(4))
            .putShort(t.year.toShort())
            .put(t.monthValue.toByte())
            .put(t.dayOfMonth.toByte())
            .put(t.hour.toByte())
            .put(t.minute.toByte())
            .put(t.second.toByte())
            .array()
    }

    /** The camera asks for a fix by notifying `01 00` on [GEOTAG_REQUEST]. */
    fun isGeotagRequest(value: ByteArray): Boolean =
        value.size >= 2 && value[0] == 0x01.toByte() && value[1] == 0x00.toByte()
}

/**
 * "New photos are ready" = the camera has raised its Wi-Fi access point for image transfer.
 * AP_STATE becomes 0x8003 (reserved image transfer, i.e. Auto
 * Image Transfer) or 0x8001 (launched, e.g. after our own launch request).
 */
internal object NewPhotoSignalDecoder {
    fun isNewPhotoSignal(characteristic: UUID, value: ByteArray): Boolean {
        if (characteristic != FujiProtocol.AP_STATE) return false
        val state = FujiProtocol.u16(value) ?: return false
        return state == FujiProtocol.AP_RESERVED_TRANSFER || state == FujiProtocol.AP_LAUNCHED
    }
}
