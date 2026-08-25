package top.rootu.dddplayer.engine

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import top.rootu.dddplayer.player.DataSourceEngineIo
import java.io.File

/**
 * Проверка шага 3: контейнер разбирается, дорожки перечисляются, HDR-метаданные
 * доходят до Kotlin — через ту же цепочку `DataSource`, что используется в
 * воспроизведении.
 *
 * Медиафайлы кладутся на устройство отдельно (`native/scripts/push-test-media.sh`),
 * а не в assets: 25 МБ в APK замедляли бы каждую установку, и все эти файлы —
 * публичные семплы FATE, не часть приложения.
 *
 * Каталог — внешний files-dir приложения, а не `/sdcard`: с Android 11 прямое
 * чтение из общей памяти требует разрешений, которых у приложения нет и не должно
 * быть, а в свой files-dir `adb push` пишет свободно.
 *
 * Тест намеренно повторяет часть утверждений нативного `engine_probe`: там
 * проверялся движок, здесь — граница JNI. Совпадение результатов по обе стороны
 * и есть доказательство, что граница ничего не теряет.
 */
@RunWith(AndroidJUnit4::class)
class NativeProbeTest {

    private val mediaDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null) ?: context.filesDir, "dddtest")
    }

    private fun media(name: String): File = File(mediaDir, name)

    @Before
    fun setUp() {
        assumeTrue("нативный движок не загрузился", NativeDemuxer.isAvailable)
        assumeTrue("нет $mediaDir — запустите push-test-media.sh", mediaDir.isDirectory)
    }

    /** Самопроверка сборки hdr-static-info: 25 байт на каноническом HDR10. */
    @Test
    fun selfTestPasses() {
        assertTrue("самопроверка hdr-static-info провалилась", NativeDemuxer.selfTest())
    }

    @Test
    fun versionReportsFfmpeg() {
        val version = NativeDemuxer.version()
        assertTrue("нет версии FFmpeg в '$version'", version.contains("7."))
    }

    /**
     * Основная проверка шага 3 по плану: файл открыт через цепочку `DataSource`,
     * получены дорожки и цвет с непустым `hdrStaticInfo`.
     *
     * Байты сравниваются с тем, что выдал нативный тест на этом же файле. Это
     * единственное место, где сравнение с константой уместно: расхождение здесь
     * означает потерю данных именно на границе JNI, а не другое мастеринг-железо.
     */
    @Test
    fun hdr10ThroughDataSourceGivesStaticInfo() {
        val file = media("hdr10tags-both.mkv")
        assumeTrue("нет ${file.name}", file.isFile)

        val probe = probeThroughDataSource(file)

        assertEquals("matroska,webm", probe.format)
        assertTrue("не найдено видео", probe.hasVideo)
        assertTrue("источник должен перематываться", probe.seekable)

        val color = probe.color
        // HDR10 без 10 бит не бывает; 8 здесь означал бы, что глубина взята не из
        // pix_fmt, и дальше на шаге 6 выбралась бы 8-битная текстура.
        assertTrue("глубина цвета ${color.bitDepth} бит", color.bitDepth >= 10)
        assertTrue("не распознан HDR: ${color.label}", color.isHdr)

        val info = color.hdrStaticInfo
        assertNotNull("hdrStaticInfo пуст на HDR10-файле", info)
        assertEquals("длина CTA-861.3 Type 1", 25, info!!.size)
        // Эти байты выдал нативный engine_probe на этом же файле. Единственное
        // место, где сравнение с константой уместно: расхождение означает потерю
        // данных именно на границе JNI, а не другой мастеринг.
        //
        // Расшифровка (u16 LE, шаг 0.00002): R(0.680,0.320) G(0.265,0.690)
        // B(0.150,0.060) W(0.3127,0.3290) — DCI-P3 D65; max 1000 кд/м²,
        // min 0.01 кд/м² (100 в единицах 0.0001), maxCLL 1000, maxFALL 300.
        assertEquals(
            "hdr-static-info разошёлся с нативным результатом",
            "00d084803ec233c4864c1db80b133d4240e8036400e8032c01",
            info.toHex()
        )
        // Порядок primaries — главная ловушка блока: в HEVC SEI и в mp4-боксе
        // `mdcv` он G,B,R, а CTA-861.3 требует R,G,B. При неверном порядке блок
        // остаётся валидным по длине, и ошибка видна только по цвету на экране.
        assertEquals("Static Metadata Descriptor ID должен быть 0 (Type 1)", 0, info[0].toInt())
        val u16 = { at: Int -> (info[at].toInt() and 0xff) or ((info[at + 1].toInt() and 0xff) shl 8) }
        val rx = u16(1); val ry = u16(3); val gx = u16(5); val gy = u16(7)
        assertTrue("x красной ($rx) должен быть больше x зелёной ($gx)", rx > gx)
        assertTrue("y зелёной ($gy) должен быть больше y красной ($ry)", gy > ry)
    }

    /**
     * Пробинг по пути файла средствами FFmpeg и пробинг через наш `AVIOContext`
     * обязаны совпасть.
     *
     * Это то самое утверждение, из которого следует, что TorrServer, SMB и
     * `LocalBridgeServer` ведут себя как локальный файл: движок в обоих случаях
     * видит одни и те же данные, различается только способ их доставки. При
     * чтении через `EngineIo` url не передаётся вообще, так что формат
     * определяется по содержимому — ровно как при чтении из сети.
     */
    @Test
    fun ioBridgeMatchesDirectPath() {
        val file = media("hdr10tags-both.mkv")
        assumeTrue("нет ${file.name}", file.isFile)

        val direct = probeByPath(file)
        val bridged = probeThroughDataSource(file)

        assertEquals(direct.format, bridged.format)
        assertEquals(direct.durationMs, bridged.durationMs)
        assertEquals(direct.bestVideoIndex, bridged.bestVideoIndex)
        assertEquals(direct.bestAudioIndex, bridged.bestAudioIndex)
        assertEquals(direct.tracks.video.size, bridged.tracks.video.size)
        assertEquals(direct.tracks.audio.size, bridged.tracks.audio.size)
        assertEquals(direct.tracks.subtitle.size, bridged.tracks.subtitle.size)
        assertEquals(direct.videoFormat.width, bridged.videoFormat.width)
        assertEquals(direct.videoFormat.height, bridged.videoFormat.height)
        assertEquals(direct.color.colorTransfer, bridged.color.colorTransfer)
        assertEquals(direct.color.colorStandard, bridged.color.colorStandard)
        assertEquals(
            direct.color.hdrStaticInfo?.toHex(),
            bridged.color.hdrStaticInfo?.toHex()
        )
    }

    /** SDR-файл не должен получить HDR-метаданные: ложный HDR вымывает картинку. */
    @Test
    fun sdrFileHasNoStaticInfo() {
        val file = media("buck480p30.mp4")
        assumeTrue("нет ${file.name}", file.isFile)

        val probe = probeThroughDataSource(file)
        assertTrue("SDR-файл распознан как HDR: ${probe.color.label}", !probe.color.isHdr)
        assertNull("у SDR-файла появился hdrStaticInfo", probe.color.hdrStaticInfo)
        assertEquals(8, probe.color.bitDepth)
    }

    /** Дорожки: подписи собраны, id — индексы потоков, выбранная помечена. */
    @Test
    fun tracksCarryStreamIndices() {
        // Файл с видео И звуком: buck480p30.mp4 здесь не годится — он без
        // аудиодорожки, и проверять на нём модель дорожек нечего.
        val file = media("dovi-p84.mov")
        assumeTrue("нет ${file.name}", file.isFile)

        val probe = probeThroughDataSource(file)
        val tracks = probe.tracks

        assertTrue("нет видеодорожек", tracks.video.isNotEmpty())
        assertTrue("нет аудиодорожек", tracks.audio.isNotEmpty())

        tracks.audio.forEach {
            assertTrue("пустая подпись дорожки ${it.id}", it.label.isNotBlank())
            assertTrue("id ${it.id} не похож на индекс потока", it.id >= 0)
        }
        assertEquals(
            "выбрана не та аудиодорожка",
            probe.bestAudioIndex,
            tracks.audio.firstOrNull { it.selected }?.id
        )
        // selectStreams принимает индексы потоков напрямую — таблица соответствий
        // между id дорожки и индексом потока не нужна и не должна появляться.
        assertTrue(
            "selectStreams не принял индексы из модели",
            withSession(file) { handle ->
                NativeDemuxer.selectStreams(handle, probe.bestVideoIndex, probe.bestAudioIndex, -1)
            }
        )
    }

    /** Dolby Vision профиль 7: `dvvC` лежит на втором видеопотоке (enhancement layer). */
    @Test
    fun dolbyVisionProfile7FindsEnhancementLayer() {
        val file = media("dovi-p7.mp4")
        assumeTrue("нет ${file.name}", file.isFile)

        val probe = probeThroughDataSource(file)
        assertEquals("не распознан профиль DV", 7, probe.color.dolbyProfile)
        assertTrue("не найден поток с dvvC", probe.dolbyStreamIndex >= 0)
        // SAR у этого файла в контейнере не указан (FFmpeg отдаёт 0:1); нормализация
        // в 1:1 обязательна, иначе ширина кадра при выводе умножается на ноль.
        assertTrue("SAR не нормализован", probe.videoFormat.pixelAspectRatio > 0f)
    }

    /** 360°-проекция из Matroska: геометрия доходит до Kotlin, а не теряется в JNI. */
    @Test
    fun sphericalDetectsEquirect360() {
        val file = media("spherical.mkv")
        assumeTrue("нет ${file.name}", file.isFile)

        val probe = probeThroughDataSource(file)
        assertEquals(Projection.EQUIRECT_360, probe.projection)
    }

    /** Демукс и seek живут: буфер наполняется, позиция после seek около цели. */
    @Test
    fun demuxFillsBufferAndSeeks() {
        val file = media("buck480p30.mp4")
        assumeTrue("нет ${file.name}", file.isFile)

        withSession(file) { handle ->
            val probe = NativeDemuxer.probe(handle)
            assertTrue(
                "selectStreams",
                NativeDemuxer.selectStreams(handle, probe.bestVideoIndex, probe.bestAudioIndex, -1)
            )
            assertTrue("start", NativeDemuxer.start(handle))

            val filled = waitFor(5000) { (NativeDemuxer.stats(handle)?.packetsRead ?: 0) > 100 }
            val stats = NativeDemuxer.stats(handle)
            assertNotNull("нет статистики", stats)
            assertTrue("буфер не наполняется: $stats", filled)
            assertTrue("байты в очередях не растут", stats!!.queuedBytes > 0)
            assertEquals("ошибки чтения локального файла", 0, stats.readErrors)

            val target = probe.durationMs / 2
            assertTrue("seek не принят", NativeDemuxer.seek(handle, target))
            val sought = waitFor(5000) { (NativeDemuxer.stats(handle)?.seeks ?: 0) > 0 }
            assertTrue("seek не выполнился", sought)

            // Ждём, пока после сброса очередей в них появятся пакеты с новой позиции.
            waitFor(5000) { (NativeDemuxer.stats(handle)?.queueStartMs ?: -1) >= 0 }
            val after = NativeDemuxer.stats(handle)!!
            // Начало очереди, а не bufferedPosition: последний прочитанный пакет к
            // этому моменту уже уехал вперёд на всю глубину буфера (до 50 с).
            val drift = after.queueStartMs - target
            assertTrue(
                "seek встал далеко от цели: queueStart=${after.queueStartMs}, цель=$target",
                after.queueStartMs >= 0 && drift > -12_000 && drift < 3_000
            )
            NativeDemuxer.stop(handle)
        }
    }

    /**
     * Обратное давление: очереди не растут без предела.
     *
     * Порог по длительности (50 с) — основной, лимит по байтам — только клапан от
     * OOM, как и в `DefaultLoadControl` при `prioritizeTimeOverSizeThresholds`.
     */
    @Test
    fun backpressureStopsGrowth() {
        val file = media("buck480p30.mp4")
        assumeTrue("нет ${file.name}", file.isFile)

        withSession(file) { handle ->
            val probe = NativeDemuxer.probe(handle)
            NativeDemuxer.selectStreams(handle, probe.bestVideoIndex, probe.bestAudioIndex, -1)
            NativeDemuxer.start(handle)

            waitFor(10_000) {
                val s = NativeDemuxer.stats(handle) ?: return@waitFor false
                s.eof || s.bufferedDurationMs >= 50_000
            }
            val first = NativeDemuxer.stats(handle)!!
            Thread.sleep(700)
            val second = NativeDemuxer.stats(handle)!!

            assertTrue(
                "очереди переросли лимит памяти: ${second.queuedBytes}",
                second.queuedBytes <= 96L * 1024 * 1024
            )
            assertTrue(
                "чтение не остановилось при полном буфере: $first -> $second",
                second.eof ||
                    second.queuedBytes == first.queuedBytes ||
                    second.bufferedDurationMs >= 50_000
            )
            NativeDemuxer.stop(handle)
        }
    }

    /** Закрытие сессии закрывает и `EngineIo`: иначе течёт соединение к TorrServer. */
    @Test
    fun closeClosesIo() {
        val file = media("buck480p30.mp4")
        assumeTrue("нет ${file.name}", file.isFile)

        val io = openIo(file)
        val counting = CountingIo(io)
        val handle = NativeDemuxer.open(null, counting)
        assertTrue(handle != 0L)
        assertEquals("EngineIo закрыт до времени", 0, counting.closes)
        NativeDemuxer.close(handle)
        assertEquals("движок не закрыл EngineIo", 1, counting.closes)
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private fun openIo(file: File): EngineIo = DataSourceEngineIo.open(
        FileDataSource(),
        DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).build()
    )

    private fun probeThroughDataSource(file: File): EngineProbe {
        val handle = NativeDemuxer.open(null, openIo(file))
        assertTrue("не открылся ${file.name} через DataSource", handle != 0L)
        try {
            return NativeDemuxer.probe(handle)
        } finally {
            NativeDemuxer.close(handle)
        }
    }

    private fun probeByPath(file: File): EngineProbe {
        val handle = NativeDemuxer.open(file.absolutePath, null)
        assertTrue("не открылся ${file.name} по пути", handle != 0L)
        try {
            return NativeDemuxer.probe(handle)
        } finally {
            NativeDemuxer.close(handle)
        }
    }

    private fun <T> withSession(file: File, body: (Long) -> T): T {
        val handle = NativeDemuxer.open(null, openIo(file))
        assertTrue("не открылся ${file.name}", handle != 0L)
        try {
            return body(handle)
        } finally {
            NativeDemuxer.close(handle)
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    /** Обёртка, считающая закрытия: проверяет владение источником в движке. */
    private class CountingIo(private val delegate: EngineIo) : EngineIo {
        var closes = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int) =
            delegate.read(buffer, offset, length)

        override fun seekTo(position: Long) = delegate.seekTo(position)
        override fun size() = delegate.size()
        override fun seekable() = delegate.seekable()
        override fun name() = delegate.name()
        override fun close() {
            closes++
            delegate.close()
        }
    }
}
