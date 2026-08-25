package top.rootu.dddplayer.engine

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.player.DataSourceEngineIo
import java.io.File

/** Реальный путь шага 5: DataSource → FFmpeg demux → MediaCodec → GL. */
@RunWith(AndroidJUnit4::class)
class NativeDecoderTest {

    private val mediaDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null) ?: context.filesDir, "dddtest")
    }

    @Before
    fun setUp() {
        assumeTrue("нативный движок не загрузился", NativeDemuxer.isAvailable)
        assumeTrue("нет $mediaDir — запустите push-test-media.sh", mediaDir.isDirectory)
    }

    @Test
    fun sdrFrameDecodesAndReachesRenderer() {
        withDecoder("buck480p30.mp4", preferTenBit = false) { decoder, probe ->
            // Ролик начинается с fade-in: первый корректно декодированный кадр
            // почти чёрный. Доходим до первого содержательного кадра, чтобы тест
            // отличал рабочий decode от нулевого буфера, но не браковал заставку.
            val luma = awaitNonFlatFrame(decoder)
            try {
                assertTrue("неизвестный декодер", decoder.decoderName.isNotBlank())
                assertEquals(probe.videoFormat.width, decoder.outputWidth)
                assertEquals(probe.videoFormat.height, decoder.outputHeight)
                assertTrue("декодер не получил пакеты", decoder.packetsIn > 0)
                assertTrue("декодер не выдал кадр", decoder.framesOut > 0)
                assertTrue("неизвестный pixel format", decoder.pixelFormat != null)

                assertTrue("плоскость Y пуста", luma.isNotEmpty())

                val renderer = NativeRenderer.offscreen(320, 180)
                assertNotNull("не создался offscreen GL", renderer)
                try {
                    assertTrue("decode → upload", decoder.uploadToRenderer(renderer!!))
                    assertTrue("draw", renderer.draw(0, NativeRenderer.ScaleMode.STRETCH))
                    val pixels = ByteArray(320 * 180 * 4)
                    assertTrue("readPixels", renderer.readPixels(pixels))
                    assertTrue("рендер дал пустой кадр", sampledRgbRange(pixels) >= 12)
                } finally {
                    renderer?.release()
                }
            } finally {
                decoder.releaseFrame()
            }
        }
    }

    @Test
    fun hdr10KeepsTenBitsAndToneMapsOnPixel() {
        withDecoder("hdr10tags-both.mkv", preferTenBit = true) { decoder, probe ->
            assertTrue("семпл определён не как 10-битный: ${decoder.streamBitDepth}",
                decoder.streamBitDepth >= 10)
            assertTrue("P010 не был запрошен", decoder.tenBitRequested)

            awaitFrame(decoder, timeoutMs = 15_000)
            try {
                val format = decoder.pixelFormat
                assertNotNull("декодер не сообщил pixel format", format)
                assertTrue("MediaCodec потерял 10 бит: $format / ${decoder.decoderName}",
                    decoder.tenBitOutput && format!!.bitDepth >= 10)

                val luma = decoder.planeBytes(0)
                assertNotNull("не читается 10-битная Y", luma)
                assertTrue("10-битная Y слишком мала", luma!!.size >= 4)
                assertTrue("в кадре нет промежуточных 10-битных кодов",
                    hasCodesBeyondEightBit(luma, format!!))

                val renderer = NativeRenderer.offscreen(320, 180)
                assertNotNull("не создался offscreen GL", renderer)
                try {
                    renderer!!.setHdrParams(
                        NativeRenderer.HdrParams.from(
                            color = probe.color,
                            displayPeakNits = 500f
                        )
                    )
                    assertTrue("10-bit decode → upload", decoder.uploadToRenderer(renderer))
                    assertTrue("HDR draw", renderer.draw(0, NativeRenderer.ScaleMode.STRETCH))
                    val pixels = ByteArray(320 * 180 * 4)
                    assertTrue("HDR readPixels", renderer.readPixels(pixels))
                    val range = sampledRgbRange(pixels)
                    assertTrue("тонмаппинг дал пустой/плоский кадр, range=$range", range >= 12)
                } finally {
                    renderer?.release()
                }
            } finally {
                decoder.releaseFrame()
            }
        }
    }

    @Test
    fun forcedLibavcodecKeepsHdrTenBitsAndUsesSameRenderer() {
        withDecoder(
            "hdr10tags-both.mkv",
            preferTenBit = true,
            forceSoftware = true
        ) { decoder, probe ->
            assertTrue("ожидался SW-декодер: ${decoder.decoderName}", !decoder.isHardwareDecode)
            assertEquals("ступень libavcodec", 4, decoder.rung)
            assertTrue("не видно libavcodec в имени: ${decoder.decoderName}",
                decoder.decoderName.startsWith("libavcodec:"))

            awaitFrame(decoder, timeoutMs = 15_000)
            try {
                val format = decoder.pixelFormat
                assertNotNull("SW не сообщил pixel format", format)
                assertTrue("SW потерял 10 бит: $format", decoder.tenBitOutput && format!!.bitDepth >= 10)
                val luma = decoder.planeBytes(0)
                assertNotNull("не читается SW Y", luma)
                assertTrue("SW кадр схлопнулся в 8 бит", hasCodesBeyondEightBit(luma!!, format!!))

                val renderer = NativeRenderer.offscreen(320, 180)
                assertNotNull("не создался offscreen GL", renderer)
                try {
                    renderer!!.setHdrParams(NativeRenderer.HdrParams.from(probe.color, 500f))
                    assertTrue("SW → тот же upload", decoder.uploadToRenderer(renderer))
                    assertTrue("SW HDR draw", renderer.draw(0, NativeRenderer.ScaleMode.STRETCH))
                    val pixels = ByteArray(320 * 180 * 4)
                    assertTrue("SW readPixels", renderer.readPixels(pixels))
                    assertTrue("SW HDR дал пустой кадр", sampledRgbRange(pixels) >= 12)
                } finally {
                    renderer?.release()
                }
            } finally {
                decoder.releaseFrame()
            }
        }
    }

    private fun withDecoder(
        name: String,
        preferTenBit: Boolean,
        forceSoftware: Boolean = false,
        body: (NativeVideoDecoder, EngineProbe) -> Unit
    ) {
        val file = File(mediaDir, name)
        assumeTrue("нет ${file.name}", file.isFile)
        val io = DataSourceEngineIo.open(
            FileDataSource(),
            DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).build()
        )
        val handle = NativeDemuxer.open(null, io)
        assertTrue("не открылся ${file.name}", handle != 0L)

        var decoder: NativeVideoDecoder? = null
        try {
            val probe = NativeDemuxer.probe(handle)
            assertTrue("нет видеопотока", probe.bestVideoIndex >= 0)
            assertTrue("selectStreams",
                NativeDemuxer.selectStreams(handle, probe.bestVideoIndex, -1, -1))
            assertTrue("start", NativeDemuxer.start(handle))
            decoder = NativeVideoDecoder.create(
                demuxHandle = handle,
                streamIndex = probe.bestVideoIndex,
                preferTenBit = preferTenBit,
                allowEscalate = false,
                sendHdrStaticInfo = true,
                forceSoftware = forceSoftware
            )
            body(decoder, probe)
        } finally {
            decoder?.release()
            NativeDemuxer.stop(handle)
            NativeDemuxer.close(handle)
        }
    }

    private fun awaitFrame(decoder: NativeVideoDecoder, timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            when (decoder.nextFrame(20)) {
                NativeVideoDecoder.Step.FRAME -> return
                NativeVideoDecoder.Step.AGAIN -> Unit
                NativeVideoDecoder.Step.EOS -> fail("EOS до первого кадра")
                NativeVideoDecoder.Step.ERROR -> fail("ошибка декодера ${decoder.decoderName}")
            }
        }
        fail("нет кадра за ${timeoutMs}ms: ${decoder.decoderName}, packets=${decoder.packetsIn}")
    }

    /** Возвращает Y первого не-заставочного кадра, оставляя этот кадр удержанным. */
    private fun awaitNonFlatFrame(decoder: NativeVideoDecoder): ByteArray {
        repeat(90) {
            awaitFrame(decoder)
            val luma = decoder.planeBytes(0)
            if (luma != null && luma.isNotEmpty() && sampledRange(luma) >= 8) return luma
            decoder.releaseFrame()
        }
        fail("за 90 кадров не появился содержательный SDR-кадр")
        throw AssertionError("unreachable")
    }

    private fun sampledRange(bytes: ByteArray): Int {
        var min = 255
        var max = 0
        val step = maxOf(1, bytes.size / 20_000)
        var i = 0
        while (i < bytes.size) {
            val value = bytes[i].toInt() and 0xff
            min = minOf(min, value)
            max = maxOf(max, value)
            i += step
        }
        return max - min
    }

    private fun sampledRgbRange(rgba: ByteArray): Int {
        var min = 255
        var max = 0
        val pixelStep = maxOf(1, rgba.size / 4 / 20_000)
        var pixel = 0
        while (pixel * 4 + 2 < rgba.size) {
            val at = pixel * 4
            for (c in 0..2) {
                val value = rgba[at + c].toInt() and 0xff
                min = minOf(min, value)
                max = maxOf(max, value)
            }
            pixel += pixelStep
        }
        return max - min
    }

    private fun hasCodesBeyondEightBit(
        bytes: ByteArray,
        format: NativeRenderer.PixelFormat
    ): Boolean {
        val shift = if (format == NativeRenderer.PixelFormat.P010) 6 else 0
        val mask = (1 shl format.bitDepth) - 1
        var sawIntermediate = false
        val words = bytes.size / 2
        val wordStep = maxOf(1, words / 100_000)
        var wordIndex = 0
        while (wordIndex < words) {
            val at = wordIndex * 2
            val raw = (bytes[at].toInt() and 0xff) or
                ((bytes[at + 1].toInt() and 0xff) shl 8)
            if (format == NativeRenderer.PixelFormat.P010) {
                assertEquals("P010 не MSB-aligned", 0, raw and 0x3f)
            }
            val code = (raw ushr shift) and mask
            if ((code and 0x3) != 0) sawIntermediate = true
            wordIndex += wordStep
        }
        return sawIntermediate
    }
}
