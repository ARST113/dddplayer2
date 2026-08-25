package top.rootu.dddplayer.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Проверка шага 6: ST 2084/HLG, BT.2390 и тот же путь в GL-шейдере. */
@RunWith(AndroidJUnit4::class)
class NativeToneMapTest {

    private var renderer: NativeRenderer? = null

    @Before
    fun setUp() {
        assumeTrue("нативный движок не загрузился", NativeDemuxer.isAvailable)
    }

    @After
    fun tearDown() {
        renderer?.release()
        renderer = null
    }

    @Test
    fun pqKnownValuesAndRoundTrips() {
        assertEquals(0f, NativeRenderer.nitsToPq(0f), 1e-7f)
        assertEquals(0.5080784f, NativeRenderer.nitsToPq(100f), 2e-6f)
        assertEquals(0.7518271f, NativeRenderer.nitsToPq(1000f), 2e-6f)
        assertEquals(1f, NativeRenderer.nitsToPq(10_000f), 2e-6f)

        for (nits in floatArrayOf(0.1f, 1f, 10f, 100f, 500f, 1000f, 4000f, 10_000f)) {
            val restored = NativeRenderer.pqToNits(NativeRenderer.nitsToPq(nits))
            val tolerance = maxOf(0.002f, nits * 2e-4f)
            assertEquals("PQ round-trip для $nits нит", nits, restored, tolerance)
        }
    }

    @Test
    fun referenceRejectsMalformedInputAndKeepsSdrUntouched() {
        val params = NativeRenderer.HdrParams(
            transfer = NativeRenderer.Transfer.SDR,
            convertGamut = false
        )
        assertNull(NativeRenderer.toneMapReference(params, floatArrayOf(0.1f, 0.2f)))

        val input = floatArrayOf(-0.2f, 0.25f, 1.2f, 0.1f, 0.5f, 0.9f)
        val out = NativeRenderer.toneMapReference(params, input)
        assertNotNull(out)
        val expected = floatArrayOf(0f, 0.25f, 1f, 0.1f, 0.5f, 0.9f)
        expected.indices.forEach { i -> assertEquals("канал $i", expected[i], out!![i], 1e-7f) }
    }

    @Test
    fun pqPeakWhiteReachesFullScale() {
        val params = pqParams()
        val peak = NativeRenderer.nitsToPq(1000f)
        val out = NativeRenderer.toneMapReference(params, floatArrayOf(peak, peak, peak))!!
        out.forEachIndexed { channel, value ->
            assertEquals("пиковый белый, канал $channel", 1f, value, 2e-4f)
        }
    }

    @Test
    fun pqBelowKneeKeepsPhysicalLuminance() {
        val params = pqParams()
        val code100 = NativeRenderer.nitsToPq(100f)
        val out = NativeRenderer.toneMapReference(params, floatArrayOf(code100, code100, code100))!!

        // Ниже колена BT.2390 не меняет свет: 100 нит на панели 500 нит — 0.2,
        // после кодирования гаммой 2.2 получаем это число.
        val expected = (100f / 500f).pow(1f / 2.2f)
        out.forEachIndexed { channel, value ->
            assertEquals("100 нит, канал $channel", expected, value, 8e-4f)
        }
    }

    @Test
    fun pqToneMapPreservesLinearChromaticity() {
        val params = pqParams()
        val input = floatArrayOf(
            NativeRenderer.nitsToPq(800f),
            NativeRenderer.nitsToPq(400f),
            NativeRenderer.nitsToPq(200f)
        )
        val out = NativeRenderer.toneMapReference(params, input)!!
        val linear = out.map { it.toDouble().pow(params.displayGamma.toDouble()) }

        assertEquals("R/G", 2.0, linear[0] / linear[1], 0.004)
        assertEquals("G/B", 2.0, linear[1] / linear[2], 0.004)
        assertTrue("кривая не должна клиппировать 800 нит", linear[0] < 1.0)
    }

    @Test
    fun fourXvrCompatibilityMatchesExtractedDefaultShader() {
        val params = NativeRenderer.HdrParams(
            transfer = NativeRenderer.Transfer.PQ,
            brightness = 1f,
            convertGamut = false,
            matchFourXvr = true
        )
        val code = NativeRenderer.nitsToPq(100f)
        val actual = NativeRenderer.toneMapReference(params, floatArrayOf(code, code, code))!!

        // Независимая запись извлечённого шейдера 4XVR для его штатного
        // GetHDRBrightness() = 0.5. Зелёный намеренно отличается: в оригинале
        // множитель 93 вместо 100.
        val linear = 100.0 / 10_000.0
        val hable = ((linear * (0.15 * linear + 0.10 * 0.50) + 0.20 * 0.02) /
                (linear * (0.15 * linear + 0.50) + 0.20 * 0.30)) - 0.02 / 0.30
        val values = doubleArrayOf(hable * 70.0, hable * 65.1, hable * 70.0)
        for (i in values.indices) values[i] = values[i].coerceAtLeast(0.0).pow(0.35)
        val vmax = values.max()
        for (i in values.indices) {
            values[i] += maxOf(values[i] - vmax, -0.1) * 0.85
            val v = values[i].coerceIn(0.0, 1.0)
            values[i] = v * v * v * -0.8 + v * v * 1.2 + v * 0.6
        }

        actual.indices.forEach { i ->
            assertEquals("4XVR channel $i", values[i], actual[i].toDouble(), 2e-5)
        }
        assertTrue("оригинальная 4XVR-коррекция зелёного потеряна", actual[1] < actual[0])
    }

    @Test
    fun dolbyVisionAutomaticallySelectsFourXvrReferencePath() {
        val dv = EngineColorInfo(
            colorStandard = EngineColorInfo.COLOR_STANDARD_BT2020,
            colorTransfer = EngineColorInfo.COLOR_TRANSFER_ST2084,
            bitDepth = 10,
            dolbyProfile = 8
        )
        val hdr10 = EngineColorInfo(
            colorStandard = EngineColorInfo.COLOR_STANDARD_BT2020,
            colorTransfer = EngineColorInfo.COLOR_TRANSFER_ST2084,
            bitDepth = 10
        )
        assertTrue(NativeRenderer.HdrParams.from(dv).matchFourXvr)
        assertTrue(!NativeRenderer.HdrParams.from(hdr10).matchFourXvr)
    }

    @Test
    fun hlgReferenceIsFiniteAndMonotonic() {
        val params = NativeRenderer.HdrParams(
            transfer = NativeRenderer.Transfer.HLG,
            displayPeakNits = 500f,
            convertGamut = false
        )
        val codes = (0..20).map { it / 20f }
        val input = FloatArray(codes.size * 3) { codes[it / 3] }
        val out = NativeRenderer.toneMapReference(params, input)!!

        var previous = -1f
        for (i in codes.indices) {
            val value = out[i * 3]
            assertTrue("HLG дал нечисло на ${codes[i]}", value.isFinite())
            assertTrue("HLG немонотонен: $previous -> $value", value + 1e-6f >= previous)
            assertTrue("HLG вне диапазона: $value", value in 0f..1f)
            previous = value
        }
        assertEquals(0f, out.first(), 1e-6f)
        assertEquals(1f, out[out.lastIndex - 2], 2e-4f)
    }

    @Test
    fun shaderMatchesCpuForPqGrays() {
        val width = 32
        val height = 16
        val r = NativeRenderer.offscreen(width, height)
        assertNotNull("не создался offscreen GL", r)
        renderer = r

        val params = pqParams()
        r!!.setHdrParams(params)

        for (nits in floatArrayOf(10f, 100f, 300f, 500f, 1000f)) {
            val requestedPq = NativeRenderer.nitsToPq(nits)
            val yCode = (64f + requestedPq * 876f).roundToInt().coerceIn(64, 940)
            val actualPq = (yCode - 64f) / 876f
            val frame = solidPlanar10(width, height, yCode)

            assertTrue(
                "upload $nits нит",
                r.upload(
                    frame,
                    intArrayOf(width * 2, (width / 2) * 2, (width / 2) * 2),
                    width,
                    height,
                    NativeRenderer.PixelFormat.YUV420P10LE,
                    NativeRenderer.Standard.BT2020,
                    fullRange = false
                )
            )
            assertTrue("draw $nits нит", r.draw(0, NativeRenderer.ScaleMode.STRETCH))

            val pixels = ByteArray(width * height * 4)
            assertTrue("readPixels $nits нит", r.readPixels(pixels))
            val at = ((height / 2) * width + width / 2) * 4
            val cpu = NativeRenderer.toneMapReference(
                params,
                floatArrayOf(actualPq, actualPq, actualPq)
            )!![0]

            for (channel in 0..2) {
                val gpu = (pixels[at + channel].toInt() and 0xff) / 255f
                assertTrue(
                    "$nits нит, канал $channel: GPU=$gpu CPU=$cpu",
                    abs(gpu - cpu) <= 0.025f
                )
            }
        }
    }

    @Test
    fun fourXvrShaderMatchesCpuReference() {
        val width = 32
        val height = 16
        val r = NativeRenderer.offscreen(width, height)
        assertNotNull("не создался offscreen GL", r)
        renderer = r
        val params = NativeRenderer.HdrParams(
            transfer = NativeRenderer.Transfer.PQ,
            brightness = 1f,
            convertGamut = false,
            matchFourXvr = true
        )
        r!!.setHdrParams(params)

        val requestedPq = NativeRenderer.nitsToPq(100f)
        val yCode = (64f + requestedPq * 876f).roundToInt().coerceIn(64, 940)
        val actualPq = (yCode - 64f) / 876f
        val frame = solidPlanar10(width, height, yCode)
        assertTrue(r.upload(
            frame,
            intArrayOf(width * 2, (width / 2) * 2, (width / 2) * 2),
            width, height,
            NativeRenderer.PixelFormat.YUV420P10LE,
            NativeRenderer.Standard.BT2020,
            fullRange = false
        ))
        assertTrue(r.draw(0, NativeRenderer.ScaleMode.STRETCH))
        val pixels = ByteArray(width * height * 4)
        assertTrue(r.readPixels(pixels))
        val at = ((height / 2) * width + width / 2) * 4
        val cpu = NativeRenderer.toneMapReference(
            params, floatArrayOf(actualPq, actualPq, actualPq)
        )!!
        for (channel in 0..2) {
            val gpu = (pixels[at + channel].toInt() and 0xff) / 255f
            assertTrue("4XVR channel $channel: GPU=$gpu CPU=${cpu[channel]}",
                abs(gpu - cpu[channel]) <= 0.025f)
        }
    }

    private fun pqParams() = NativeRenderer.HdrParams(
        transfer = NativeRenderer.Transfer.PQ,
        displayPeakNits = 500f,
        masteringPeakNits = 1000f,
        displayGamma = 2.2f,
        convertGamut = false
    )

    private fun solidPlanar10(width: Int, height: Int, yCode: Int): Array<ByteArray?> {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val y = ByteArray(width * height * 2)
        val u = ByteArray(chromaWidth * chromaHeight * 2)
        val v = ByteArray(chromaWidth * chromaHeight * 2)
        fillU16(y, yCode)
        fillU16(u, 512)
        fillU16(v, 512)
        return arrayOf(y, u, v)
    }

    private fun fillU16(bytes: ByteArray, value: Int) {
        var i = 0
        while (i < bytes.size) {
            bytes[i] = (value and 0xff).toByte()
            bytes[i + 1] = ((value ushr 8) and 0xff).toByte()
            i += 2
        }
    }
}
