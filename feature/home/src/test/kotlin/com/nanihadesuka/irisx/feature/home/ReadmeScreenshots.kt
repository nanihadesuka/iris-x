package com.nanihadesuka.irisx.feature.home

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.core.designsystem.IrisXTheme
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.CatalogPhase
import com.nanihadesuka.irisx.domain.model.CatalogState
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.model.TransferState
import com.nanihadesuka.irisx.domain.model.CameraCandidate
import com.nanihadesuka.irisx.feature.pairing.PairingScreen
import com.nanihadesuka.irisx.feature.pairing.PairingStage
import com.nanihadesuka.irisx.feature.pairing.PairingUiState
import com.nanihadesuka.irisx.feature.settings.SettingsScreen
import com.nanihadesuka.irisx.feature.settings.SettingsUiState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Renders the images in the README from the real screens, with invented photos and data.
 * Run with: IRISX_SCREENSHOTS=1 ./gradlew :feature:home:testDebugUnitTest --tests "*ReadmeScreenshots*"
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h892dp-xxhdpi")
class ReadmeScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out = File("../../docs/screenshots").apply { mkdirs() }

    @Before fun only() {
        assumeTrue(System.getenv("IRISX_SCREENSHOTS") != null)
        Robolectric.setupContentProvider(FakePhotos::class.java, FakePhotos.AUTHORITY)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(android.bluetooth.BluetoothAdapter.getDefaultAdapter()).setEnabled(true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions("android.permission.BLUETOOTH_CONNECT")
        app.getSystemService(android.net.wifi.WifiManager::class.java).isWifiEnabled = true
    }

    @After fun end() { stopKoin() }

    @Test fun mainTabs() {
        val newest = System.currentTimeMillis() - HOUR_MS
        val phone = (1..14).map { i ->
            val time = newest - i * HOUR_MS * 5
            SavedPhoto(FakePhotos.uri(i), "DSCF%04d.JPG".format(1000 - i), time, 8_400_000L + i * 90_000, takenAtMillis = time, queuedForBackup = i % 4 == 0)
        }
        val card = (1..24).map { i ->
            CatalogEntry(2000 - i, "DSCF%04d.JPG".format(1001 - i), 9_000_000, fakePhotoJpeg(i + 30), 1, "202511%02dT101530".format(28 - i / 6), 6240, 4160)
        }
        phone.forEachIndexed { n, photo ->
            val bytes = fakePhotoJpeg(n + 1)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            thumbnails.put("${photo.uri}@360", ShownPhoto(bitmap.asImageBitmap(), visibleArea(bitmap, null)))
        }
        val catalog = CatalogState(CatalogPhase.Live, card, card.map { it.handle }, card.size)
        val camera = Camera("00:00:00:00:00:00", "X100VI")
        val connection = mockk<com.nanihadesuka.irisx.domain.repository.CameraConnection>(relaxed = true) {
            every { state } returns MutableStateFlow(ConnectionState.Connected)
            every { lastLocationSentMillis } returns MutableStateFlow(System.currentTimeMillis() - 60_000)
            every { lastConnectedMillis } returns MutableStateFlow(System.currentTimeMillis())
            every { newPhotoSignals } returns flowOf()
        }
        val activity = mockk<com.nanihadesuka.irisx.domain.repository.SyncActivity>(relaxed = true) { every { transfer } returns MutableStateFlow(TransferState.Idle) }
        val settings = mockk<com.nanihadesuka.irisx.domain.repository.SettingsRepository>(relaxed = true)
        every { settings.settings } returns flowOf(Settings())
        val cameras = mockk<com.nanihadesuka.irisx.domain.repository.CameraRepository>(relaxed = true) { every { pairedCamera } returns flowOf(camera) }
        val store = mockk<com.nanihadesuka.irisx.domain.repository.PhotoStore>(relaxed = true) { every { recent } returns flowOf(phone) }
        val browser = mockk<com.nanihadesuka.irisx.domain.usecase.CameraBrowser>(relaxed = true) { every { state } returns MutableStateFlow(catalog) }
        val compressor = mockk<com.nanihadesuka.irisx.domain.usecase.PhotoCompressor>(relaxed = true)
        every { compressor.state } returns MutableStateFlow(com.nanihadesuka.irisx.domain.model.CompressionProgress())
        every { compressor.finishing } returns MutableStateFlow(emptySet())
        val video = mockk<com.nanihadesuka.irisx.domain.usecase.VideoCompressor>(relaxed = true) { every { state } returns MutableStateFlow(null) }
        startKoin {
            modules(module {
                viewModel { HomeViewModel(cameras, connection, activity, settings, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), store, browser, mockk(relaxed = true)) }
                viewModel { CameraViewModel(browser, store, connection, activity, settings) }
                viewModel { LibraryViewModel(store, compressor, video, settings, browser) }
            })
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            IrisXTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { MainShell(onPair = {}, settings = {
                    SettingsScreen(SettingsUiState(Settings(), camera, listOf("12:01:10  Connected to X100VI", "12:01:14  Location sent"), photoSizes = phone.map { it.sizeBytes }), {}, {}, {}, {}, {}, {}, {}, {})
                }) }
            }
        }
        settle()
        shot("home")
        for ((tab, name) in listOf("Library" to "library", "Camera" to "camera", "Settings" to "settings")) {
            // Under the paused test clock a tab change can be dropped; a second click on the selected tab does nothing.
            repeat(2) {
                compose.onNodeWithText(tab).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                settle()
            }
            settle()
            shot(name)
        }
    }

    @Test fun pairing() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            IrisXTheme { Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { PairingScreen(PairingUiState(PairingStage.Searching, listOf(CameraCandidate("AA", "X100VI-1A2B", -48))), false, {}, {}, {}, {}, {}) } }
        }
        settle()
        shot("pairing")
    }

    /** Lets flows, animations and the thumbnails loading on other threads finish. Never idles, because some text ticks. */
    private fun settle() = repeat(30) {
        Thread.sleep(150)
        compose.mainClock.advanceTimeBy(300)
    }

    private fun shot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}

/** Serves invented photos at content:// addresses, as the phone's media store would. */
class FakePhotos : ContentProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val index = uri.lastPathSegment!!.toInt()
        val file = File.createTempFile("fake", ".jpg", context!!.cacheDir).apply { writeBytes(fakePhotoJpeg(index)) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
    override fun insert(u: Uri, v: ContentValues?) = null
    override fun delete(u: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "irisx.fake"
        fun uri(index: Int) = "content://$AUTHORITY/$index"
    }
}

private val SKIES = listOf(
    intArrayOf(0xFF1B2A49.toInt(), 0xFFE76F51.toInt(), 0xFFF4A261.toInt()),
    intArrayOf(0xFF264653.toInt(), 0xFF2A9D8F.toInt(), 0xFFE9C46A.toInt()),
    intArrayOf(0xFF3A0CA3.toInt(), 0xFFF72585.toInt(), 0xFFFFB703.toInt()),
    intArrayOf(0xFF023E8A.toInt(), 0xFF48CAE4.toInt(), 0xFFCAF0F8.toInt()),
    intArrayOf(0xFF2B2D42.toInt(), 0xFF8D99AE.toInt(), 0xFFEDF2F4.toInt()),
)

/** An invented landscape: a sky, a sun and layers of hills, different for each [seed]. */
internal fun fakePhotoJpeg(seed: Int): ByteArray {
    val random = Random(seed)
    val w = 1200
    val h = 800
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val sky = SKIES[seed.mod(SKIES.size)]
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.shader = LinearGradient(0f, 0f, 0f, h * 0.8f, sky, null, Shader.TileMode.CLAMP)
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = null
    paint.color = Color.argb(235, 255, 244, 214)
    canvas.drawCircle(random.nextInt(150, 1050).toFloat(), random.nextInt(170, 440).toFloat(), random.nextInt(35, 90).toFloat(), paint)
    for (layer in 0 until 4) {
        val base = h * (0.56f + layer * 0.11f)
        val phase = random.nextFloat() * 6f
        val rise = random.nextInt(25, 80)
        val path = Path().apply {
            moveTo(0f, h.toFloat())
            for (x in 0..w step 20) lineTo(x.toFloat(), base + rise * kotlin.math.sin(x / (170f + layer * 40) + phase))
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        val dark = 1f - (layer + 1) * 0.22f
        paint.color = Color.rgb((Color.red(sky[0]) * dark).toInt(), (Color.green(sky[0]) * dark).toInt(), (Color.blue(sky[0]) * dark).toInt())
        canvas.drawPath(path, paint)
    }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
}
