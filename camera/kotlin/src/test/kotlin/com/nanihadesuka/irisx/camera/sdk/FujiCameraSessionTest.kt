package com.nanihadesuka.irisx.camera.sdk

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.net.SocketFactory

class FujiCameraSessionTest {
    private suspend fun FakeCamera.open(mode: SessionMode = SessionMode.PhotoView, log: (String) -> Unit = {}) =
        FujiCameraSession.open(SocketFactory.getDefault(), "127.0.0.1", "phone", mode, log, port)

    @Test
    fun handshakeMatchesTheCapture() = runBlocking {
        FakeCamera().use { camera ->
            camera.open().close()
            val sent = camera.initRequest
            assertEquals(82, sent.size)
            assertArrayEquals(hex("52 00 00 00 01 00 00 00 F2 E4 53 8F AD A5 48 5D 87 B2 7F 0B D3 D5 DE D0 00 00 00 00"), sent.copyOf(28))
            assertArrayEquals("phone".toByteArray(Charsets.UTF_16LE), sent.copyOfRange(28, 38))
        }
    }

    @Test
    fun openSetsTheModeThenNegotiatesTheVersion() = runBlocking {
        FakeCamera().use { camera ->
            camera.open().close()
            assertEquals(PtpOp.OPEN_SESSION, camera.operations.first())
            assertEquals(FujiProp.FUNCTION_MODE, camera.writes[0].first)
            assertArrayEquals(hex("14 00"), camera.writes[0].second)
            assertEquals(FujiProp.VERSION_PHOTO_VIEW, camera.writes[1].first)
            assertArrayEquals(hex("03 00 00 00"), camera.writes[1].second)
            assertEquals(PtpOp.CLOSE_SESSION, camera.operations.last())
        }
    }

    @Test
    fun reservedModeUsesItsOwnNumbersAndReportsTheQueue() = runBlocking {
        FakeCamera().use { camera ->
            val session = camera.open(SessionMode.ReservedTransfer)
            assertTrue(PtpOp.GET_OBJECT_INFO in camera.operations)
            assertArrayEquals(hex("15 00"), camera.writes[0].second)
            assertEquals(FujiProp.VERSION_RESERVED_TRANSFER, camera.writes[1].first)
            assertEquals(1, session.reservedCount)
            assertEquals("DSCF0395.JPG", session.nextReserved()?.name)
            session.close()
        }
    }

    @Test
    fun listsTheCardNewestFirst() = runBlocking {
        FakeCamera().use { camera ->
            val session = camera.open()
            val listing = session.listCard()
            assertEquals(listOf(13, 12, 11), listing.handles)
            assertEquals(12, listing.countsByDate.values.sum())
            assertEquals(5, listing.countsByDate.values.first())
            session.close()
        }
    }

    @Test
    fun readsInPiecesAndReportsProgress() = runBlocking {
        val file = ByteArray(3_500_000) { (it % 251).toByte() }
        FakeCamera(file).use { camera ->
            camera.maxPartial = 0xBFFFE0
            val session = camera.open()
            val out = ByteArrayOutputStream()
            var last = 0L
            session.read(13, file.size.toLong(), out) { last = it }
            assertArrayEquals(file, out.toByteArray())
            assertEquals(file.size.toLong(), last)
            assertEquals(listOf(13, 0, 0x100000), camera.readRequests[0])
            assertEquals(0x300000, camera.readRequests[3][1])
            assertEquals(4, camera.readRequests.size)
            session.close()
        }
    }

    @Test
    fun resumesFromAnOffset() = runBlocking {
        val file = ByteArray(2_000_000) { (it % 13).toByte() }
        FakeCamera(file).use { camera ->
            camera.maxPartial = 0x100000
            val session = camera.open()
            val out = ByteArrayOutputStream()
            session.read(13, file.size.toLong(), out, startAt = 1_500_000)
            assertArrayEquals(file.copyOfRange(1_500_000, file.size), out.toByteArray())
            session.close()
        }
    }

    @Test
    fun cancelStopsAtTheNextChunkAndTheSessionStillWorks() = runBlocking {
        val file = ByteArray(5_000_000)
        FakeCamera(file).use { camera ->
            camera.maxPartial = 0x100000
            val session = camera.open()
            val sink = object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = session.cancelRead()
            }
            try {
                session.read(13, file.size.toLong(), sink)
                fail("It should have stopped")
            } catch (e: ReadCancelledException) {
                assertEquals(1, camera.readRequests.size)
            }
            assertNotNull(session.objectInfo(13))
            session.close()
        }
    }

    @Test
    fun cancelWithNoReadDoesNotStopTheNextOne() = runBlocking {
        val file = ByteArray(1000)
        FakeCamera(file).use { camera ->
            val session = camera.open()
            session.cancelRead()
            val out = ByteArrayOutputStream()
            session.read(13, file.size.toLong(), out)
            assertEquals(1000, out.size())
            session.close()
        }
    }

    @Test
    fun retriesWhileTheCameraIsBusy() = runBlocking {
        FakeCamera(busyFirst = 3).use { camera ->
            val session = camera.open()
            assertEquals("DSCF0395.JPG", session.objectInfo(13)?.name)
            session.close()
        }
    }

    @Test
    fun nothingEverWritesToTheCard() = runBlocking {
        FakeCamera(ByteArray(100)).use { camera ->
            val session = camera.open()
            session.listCard()
            session.objectInfo(13)
            session.thumbnail(13)
            session.setQuality(ImageQuality.Original)
            session.read(13, 100, ByteArrayOutputStream())
            session.close()
            assertFalse(camera.operations.any { it !in PtpOp.ALLOWED })
            assertFalse(DELETE_OBJECT in PtpOp.ALLOWED)
            assertTrue(camera.writes.all { it.first in FujiProp.WRITABLE })
        }
    }

    private companion object {
        const val DELETE_OBJECT = 0x100B
    }
}

class ParsersTest {
    @Test
    fun objectInfoFromTheCapture() {
        val info = parseObjectInfo(1, FakeCamera.OBJECT_INFO)!!
        assertEquals("DSCF0395.JPG", info.name)
        assertEquals(1991073L, info.sizeBytes)
        assertEquals(0x3801, info.objectFormat)
        assertEquals(3840, info.pixelWidth)
        assertEquals(2160, info.pixelHeight)
        assertEquals("20261030T192810", info.capturedAt)
        assertEquals(1, info.orientation)
    }

    @Test
    fun changesFromTheCapture() {
        val changes = parseChanges(FakeCamera.CHANGES)
        assertEquals(4, changes.size)
        assertEquals(PropChange(FujiProp.SETTLED, 0x12), changes[0])
        assertEquals(PropChange(FujiProp.RESERVED_COUNT, 1), changes[2])
        assertTrue(parseChanges(hex("00 00")).isEmpty())
    }

    @Test
    fun handlesWithOrWithoutACount() {
        assertEquals(listOf(5, 6), parseHandles(hex("02 00 00 00 05 00 00 00 06 00 00 00")))
        assertEquals(listOf(5, 6), parseHandles(hex("05 00 00 00 06 00 00 00")))
        assertTrue(parseHandles(ByteArray(0)).isEmpty())
    }

    @Test
    fun truncatedAnswersDoNotThrow() {
        assertEquals(null, parseObjectInfo(1, ByteArray(10)))
        assertTrue(parseCountsByDate(hex("05 00 00 00 00")).isEmpty())
        assertTrue(parseChanges(hex("03 00 00 DF")).isEmpty())
    }

    @Test
    fun largeOffsetsUseTheFourthParameter() {
        assertEquals(listOf(1, 0, 16), FujiCameraSession.partialParams(1, 0, 16).toList())
        assertEquals(listOf(1, 5, 16, 1), FujiCameraSession.partialParams(1, 0x100000005L, 16).toList())
    }
}
