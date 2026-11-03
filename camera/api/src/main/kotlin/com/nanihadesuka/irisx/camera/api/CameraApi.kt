package com.nanihadesuka.irisx.camera.api

import javax.net.SocketFactory

/*
 * The whole public surface of a camera backend. The app sees this and nothing else; each backend module keeps everything
 * but its entry point internal.
 */

/** A read stopped on request; nothing was saved. */
class ReadCancelled : Exception("Read cancelled")

/** An open PTP session with the camera over Wi-Fi. */
interface PhotoSession {
    /** The next photo the camera has reserved for this phone (always object handle 1), or null when none is left. */
    suspend fun nextReserved(): PhotoRef?

    /** The newest [max] photos of the camera with their thumbnails. */
    suspend fun catalogPage(max: Int): List<CatalogEntry>
    suspend fun download(photo: PhotoRef, sink: java.io.OutputStream)

    /** The same, reporting bytes received where the session can tell. */
    suspend fun download(photo: PhotoRef, sink: java.io.OutputStream, onProgress: (Long) -> Unit) = download(photo, sink)

    /** Runs the catalog start-up and returns every object handle on the card, newest first. */
    suspend fun catalogHandles(): List<Int> = error("Not supported")

    /** The same start-up, with the per-date counts the camera answers along the way. */
    suspend fun catalogListing(): CardFingerprint =
        CardFingerprint(catalogHandles(), emptyMap())

    /** Asks the camera to stop the read in progress, if any. [download] then throws [ReadCancelled]. */
    fun cancelRead() = Unit

    /** Info and thumbnail of one handle, or null when the camera could not read it. */
    suspend fun catalogEntry(handle: Int, withThumbnail: Boolean = true): CatalogEntry? = error("Not supported")

    /** How many photos the camera queued for this transfer, once it has said. */
    fun reservedCount(): Int? = null

    /** Whether reads return the original file (true) or the camera's small preview (false). */
    suspend fun requestOriginals(originals: Boolean): Unit = error("Not supported")

    /** Announces [handle] for the next [download]. */
    suspend fun photoInfo(handle: Int): PhotoRef? = error("Not supported")
}


/** A session the gateway can end. The app only ever sees the [PhotoSession] side. */
interface CameraSessionHandle : PhotoSession, AutoCloseable

/** The camera's Wi-Fi, already joined. [platformNetwork] is android.net.Network, opaque here so this module stays plain Kotlin. */
class JoinedNetwork(val socketFactory: SocketFactory, val platformNetwork: Any)

/** One way of running a camera session. */
interface CameraBackend {
    suspend fun open(network: JoinedNetwork, phoneName: String, purpose: SessionPurpose, onAttempt: (Int) -> Unit, log: (String) -> Unit): CameraSessionHandle
}
