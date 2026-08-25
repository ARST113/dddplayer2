package top.rootu.dddplayer.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Проверка шага 5: 10/12/16 бит доходят до текстуры и считаются правильно.
 *
 * Критерий шага — «10 бит доходят до текстуры (проверка градиента, отсутствие
 * бэндинга)». Сформулировать его проверяемо сложнее, чем кажется: любой
 * 10-битный кадр можно загрубить до 8 бит, и картинка останется похожей, а
 * попиксельное сравнение с эталоном — пройдёт. Разница между 8 и 10 битами
 * видна не в отдельном пикселе (там она максимум 2 LSB из 1023), а в том,
 * сколько РАЗНЫХ уровней выживает на градиенте. Поэтому главная проверка —
 * [tenBitGradientKeepsAllLevels], и она устроена как триангуляция: один и тот же
 * градиент прогоняется через три комбинации «глубина источника × точность
 * цели», и ни одна из них по отдельности не может объяснить результат другой.
 *
 * Остальные тесты проверяют арифметику, а не глубину: матрица на глубине 10
 * (границы ограниченного диапазона там ДРУГИЕ, см.
 * [canonicalTenBitValuesMatchStandard]), раскладка P010 против планарной,
 * резервный путь загрузки против основного, stride в байтах против
 * `GL_UNPACK_ROW_LENGTH` в текселях.
 *
 * Кадры генерирует Kotlin и передаёт ОДНИ И ТЕ ЖЕ массивы в GL и в эталон.
 * Если бы кадр генерировали в native для обеих сторон, ошибка генератора
 * вычиталась бы сама из себя, и тест проходил бы на неверных данных.
 *
 * Читается всё через `readPacked10`: у поверхности EGL точность 8 бит, и
 * `readPixels` на 10-битном кадре показывал бы ровно ту потерю, которую шаг
 * обязан исключить.
 */
@RunWith(AndroidJUnit4::class)
class NativeRender10Test {

    /** Сторона плитки цветности в координатах яркости; см. [sweep]. */
    private val tile = 16

    /**
     * Допуск против эталона swscale, в LSB десятибитного кода.
     *
     * Он заметно свободнее восьмибитного (там max 4), и не из лени. swscale на
     * высокой глубине работает не в 10 битах и не в 16, а в 15-битном
     * промежуточном представлении: 10-битный код входит как `code << 5`, то есть
     * максимум становится 32736 при шкале 32767. Это ~0.1 % систематического
     * расхождения с нормировкой на `2^n − 1`, которую делает шейдер, плюс
     * усиление 1.167 от раскрытия ограниченного диапазона — итого около 1.5 LSB
     * ещё до округлений. Требовать здесь 4 LSB значило бы подгонять шейдер под
     * внутреннее устройство swscale.
     *
     * Замер на Mali-G78: max 5 и среднее 1.24 в худшем случае (ограниченный
     * диапазон, 12 и 16 бит), max 1–2 на полном диапазоне — там усиления нет, и
     * расхождение сразу падает вчетверо, что подтверждает разбор выше. Допуск
     * взят на один LSB свободнее худшего замера, как и на шаге 4.
     *
     * Точность при этом не остаётся непроверенной, она проверяется в другом
     * месте: [canonicalTenBitValuesMatchStandard] сверяет значения, посчитанные
     * по ITU-R вручную, с допуском 4 LSB, а [tenBitGradientKeepsAllLevels] —
     * число выживших уровней. Здесь же проверяется, что применена ТА матрица и
     * ТОТ диапазон, и снизу этот допуск ограничен
     * [wrongStandardIsDetectableAt10Bit].
     */
    private val maxTolerance = 6

    private val meanTolerance = 1.5

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

    // ───────────────────────────── глубина ─────────────────────────────

    /**
     * Главная проверка шага: на 10-битном градиенте выживают все уровни.
     *
     * Развёртка — 877 столбцов, яркость 64…940, то есть весь ограниченный
     * диапазон 10-битного кода по одному шагу на столбец. После раскрытия
     * диапазона выходной код равен `round(1023·(Y−64)/876)`, шаг 1.168 LSB —
     * больше единицы, поэтому ни два соседних значения не могут слиться, и
     * правильный конвейер обязан вернуть ровно 877 различных уровней.
     *
     * Одного этого числа мало: 877 уровней могло бы означать и «10 бит дошли»,
     * и «цель случайно оказалась точнее, чем мы думаем». Поэтому тот же градиент
     * прогоняется ещё дважды:
     *  - 10-битный источник в 8-битную цель: шаг падает до 0.29 LSB, уровней
     *    остаётся ~256 — это показывает, что уровни считает не сам по себе
     *    подсчёт, а точность цели;
     *  - 8-битный источник (16…235, 220 уровней) в 10-битную цель: ~220 — это
     *    показывает, что 10-битная цель не создаёт уровни из ничего.
     *
     * Ни одна из трёх цифр не выводится из двух других, и подделать все три
     * одновременно нельзя ни загрублением загрузки, ни подменой цели.
     *
     * Монотонность проверяется отдельно: набор различных значений большого
     * размера даёт и шум, а градиент обязан не убывать.
     */
    @Test
    fun tenBitGradientKeepsAllLevels() {
        val levels = 877  // 940 − 64 + 1
        val w = levels
        val h = 4
        val r = offscreen(w, h)
        r.setChromaFilter(false)

        val ramp10 = ramp(w, h, NativeRenderer.PixelFormat.YUV420P10LE, 64, 940)
        val ramp8 = ramp(w, h, NativeRenderer.PixelFormat.YUV420P, 16, 235)

        val tenToTen = gradientLevels(r, ramp10, tenBit = true)
        val tenToEight = gradientLevels(r, ramp10, tenBit = false)
        val eightToTen = gradientLevels(r, ramp8, tenBit = true)

        val report = "градиент: 10→10 бит $tenToTen уровней, 10→8 $tenToEight, 8→10 $eightToTen"
        android.util.Log.i("DddEngine", report)
        println(report)

        // Порог 860, а не ровно 877: несколько слияний может дать округление во
        // float у самого края шкалы. Потеря даже одного бита обрушила бы число
        // вдвое с лишним, так что запас в 2 % ничего не маскирует.
        assertTrue("10 бит не дошли до текстуры — $report", tenToTen >= 860)
        assertTrue("нет монотонности: градиент не градиент", monotone(r, ramp10))

        // 8-битная цель обязана схлопнуть тот же кадр примерно в 256 уровней.
        // Если она этого не делает, значит «10 бит» выше меряет что-то другое.
        assertTrue("8-битная цель отдала $tenToEight уровней — подсчёт не про глубину",
            tenToEight in 200..300)
        // И зеркально: 8-битный источник не должен разбогатеть от 10-битной цели.
        assertTrue("8-битный источник дал $eightToTen уровней в 10-битной цели",
            eightToTen in 190..260)
        assertTrue("10-битный путь не выигрывает у 8-битного — $report",
            tenToTen >= 3 * eightToTen)
    }

    // ───────────────────────────── цвет ─────────────────────────────

    /**
     * Конверсия на высокой глубине совпадает со swscale — для всех раскладок и
     * обеих реалистичных матриц.
     *
     * Прореживание перечислено полностью (4:2:0, 4:2:2, 4:4:4), потому что в
     * 4XVR на каждую комбинацию «глубина × прореживание» приходилась своя
     * функция `ConvertData_*_10Bit` с отдельной NEON-упаковкой — семь функций,
     * семь мест для ошибки. Здесь это строки таблицы формата, и проверить надо
     * именно то, что таблица заполнена верно.
     *
     * Фильтр цветности — ближайший сосед: при текстурных координатах на [0,1]
     * отсчёт цветности для пикселя x равен `x >> 1`, ровно как у `SWS_POINT`.
     * Способ интерполяции перестаёт участвовать, и остаётся сравнение одной
     * арифметики с другой.
     */
    @Test
    fun colorMatchesSwscale16() {
        val formats = listOf(
            NativeRenderer.PixelFormat.YUV420P10LE,
            NativeRenderer.PixelFormat.YUV420P12LE,
            NativeRenderer.PixelFormat.YUV420P16LE,
            NativeRenderer.PixelFormat.YUV422P10LE,
            NativeRenderer.PixelFormat.YUV444P10LE
        )
        val w = 256
        val h = 128
        val r = offscreen(w, h)
        r.setChromaFilter(false)

        var pathSeen: NativeRenderer.UploadPath? = null
        for (format in formats) {
            val frame = sweep(w, h, format)
            // BT.2020 — не роскошь: весь HDR-материал шага 6 придёт именно с ней,
            // а BT.709 остаётся для 10-битного SDR, которого тоже немало.
            for (standard in listOf(NativeRenderer.Standard.BT709, NativeRenderer.Standard.BT2020)) {
                for (fullRange in listOf(false, true)) {
                    val gl = renderPacked(r, frame, standard, fullRange)
                    pathSeen = r.uploadPath()
                    val diff = compare10(gl, reference16(frame, standard, fullRange))
                    val label = "$format $standard ${rangeName(fullRange)}"
                    android.util.Log.i("DddEngine", "sws16 $label: $diff")
                    assertTrue(
                        "$label: GL разошёлся со swscale, $diff",
                        diff.max <= maxTolerance && diff.mean <= meanTolerance
                    )
                }
            }
        }
        // Заодно фиксируется, каким путём шла загрузка: если тут окажется BYTE,
        // значит 16-битные плоскости загрубились ещё до шейдера, и совпадение
        // выше говорило бы лишь о том, что эталон загрубился так же.
        assertTrue("16-битный кадр загружен восьмибитным путём: $pathSeen",
            pathSeen == NativeRenderer.UploadPath.NORM16 ||
                pathSeen == NativeRenderer.UploadPath.BYTE_PAIR)
    }

    /**
     * Контрольный выстрел в упор: тот же кадр, эталон посчитан ДРУГОЙ матрицей.
     *
     * Без этой проверки [maxTolerance] ничем не ограничен снизу: с достаточно
     * щедрым допуском «совпадает со swscale» выполнялось бы для любой из трёх
     * матриц. Порог задан отношением к совпадающему случаю, а не абсолютным
     * числом: абсолютное расхождение зависит от того, сколько пикселей ушло в
     * клиппинг, а отношение — нет.
     */
    @Test
    fun wrongStandardIsDetectableAt10Bit() {
        val frame = sweep(256, 128, NativeRenderer.PixelFormat.YUV420P10LE)
        val r = offscreen(frame.width, frame.height)
        r.setChromaFilter(false)

        val gl = renderPacked(r, frame, NativeRenderer.Standard.BT709, false)
        val match = compare10(gl, reference16(frame, NativeRenderer.Standard.BT709, false))

        val wrong = mapOf(
            "BT.601" to compare10(gl, reference16(frame, NativeRenderer.Standard.BT601, false)),
            "BT.2020" to compare10(gl, reference16(frame, NativeRenderer.Standard.BT2020, false)),
            "full range" to compare10(gl, reference16(frame, NativeRenderer.Standard.BT709, true))
        )

        for ((name, diff) in wrong) {
            assertTrue(
                "$name не отличается от BT.709 limited: $diff против совпадающего $match",
                diff.max >= 3 * match.max && diff.mean >= 2 * match.mean
            )
            // И отдельно — что различие заметно, а не только пропорционально:
            // 32 LSB из 1023 это те же ~3 % шкалы, что 8 из 255 на шаге 4.
            assertTrue("$name расходится меньше чем на 3 % шкалы: $diff", diff.max >= 32)
        }
        assertTrue("совпадающий случай вне допуска: $match", match.max <= maxTolerance)
    }

    /**
     * Значения по ITU-R для глубины 10, посчитанные вручную.
     *
     * Здесь проверяется то, что эталон swscale поймать не мог бы: границы
     * ограниченного диапазона на глубине 10 — это 64 и 940, а не 16 и 235,
     * умноженные на что-нибудь. Выглядят они инвариантными к глубине, но не
     * являются ими: ITU-R масштабирует границы как `2^(n−8)`, а нормировка идёт
     * на `2^n − 1`, и 16/255 = 0.062745 против 64/1023 = 0.062561. По цветности
     * ошибка «взять восьмибитные константы» доходит до 2.7 LSB десятибитного
     * кода, а на красном ниже — до 6 LSB, то есть допуск 4 её ловит.
     *
     * Допуск 4 LSB из 1023 — это 0.4 %. Отличить им 8 бит от 10 нельзя в
     * принципе (любое значение лежит не дальше 2 LSB от восьмибитной сетки), и
     * задача теста не в этом: он проверяет константы матрицы, а глубину
     * проверяет [tenBitGradientKeepsAllLevels].
     */
    @Test
    fun canonicalTenBitValuesMatchStandard() {
        val w = 32
        val h = 16
        val r = offscreen(w, h)
        r.setChromaFilter(false)

        val cases = listOf(
            // Y, Cb, Cr → R, G, B (BT.709 limited, 10 бит)
            Case(64, 512, 512, intArrayOf(0, 0, 0), "чёрный"),
            Case(940, 512, 512, intArrayOf(1023, 1023, 1023), "белый"),
            Case(502, 512, 512, intArrayOf(511, 511, 511), "серый 50 %"),
            // Красный: Y = 64 + 876·0.2126, Cb = 512 + 896·(−0.11458),
            // Cr = 512 + 896·0.5. Обратно даёт (1023, 0, 0) с клиппингом в нуле.
            Case(250, 409, 960, intArrayOf(1023, 0, 0), "красный"),
            // Смешанный: ни один канал не в клиппинге, поэтому ошибка в любой из
            // девяти констант матрицы видна здесь, а не гасится ограничением.
            Case(502, 400, 700, intArrayOf(850, 435, 274), "смешанный")
        )

        for (case in cases) {
            val frame = solid(w, h, case.y, case.cb, case.cr)
            val gl = renderPacked(r, frame, NativeRenderer.Standard.BT709, false)
            val px = gl[(h / 2) * w + w / 2]
            val got = intArrayOf(px and 0x3ff, (px ushr 10) and 0x3ff, (px ushr 20) and 0x3ff)
            for (c in 0..2) {
                assertTrue(
                    "${case.name} (Y=${case.y} Cb=${case.cb} Cr=${case.cr}) канал $c: " +
                        "получено ${got[c]}, ожидалось ${case.rgb[c]}",
                    abs(got[c] - case.rgb[c]) <= 4
                )
            }
            assertEquals("${case.name}: альфа", 3, (px ushr 30) and 3)
        }
    }

    /**
     * P010 из тех же кодов даёт тот же цвет, что планарный 10-битный кадр.
     *
     * Это единственная проверка выравнивания значащих бит, и она обязана быть
     * точной: в P010 десять бит лежат в СТАРШИХ разрядах слова, и пропущенный
     * сдвиг даёт картинку в 64 раза светлее — с клиппингом почти везде, то есть
     * заметную сразу. Хуже другой исход: лишний сдвиг в обратную сторону даёт
     * картинку в 64 раза темнее, и на тёмном материале она выглядит просто
     * «контрастной».
     *
     * Со swscale P010 не сверяется намеренно: как ВХОД он поддерживается не во
     * всех сборках, и тест на доступность эталона превратился бы в
     * пропускаемый.
     */
    @Test
    fun p010MatchesPlanar10() {
        val w = 256
        val h = 128
        val r = offscreen(w, h)
        r.setChromaFilter(false)

        val planar = sweep(w, h, NativeRenderer.PixelFormat.YUV420P10LE)
        val p010 = sweep(w, h, NativeRenderer.PixelFormat.P010)
        // Кадры обязаны нести одни и те же коды, иначе сравнивать нечего. Байты
        // при этом РАЗНЫЕ — в этом и смысл, — поэтому проверяется размер, а не
        // содержимое: планарные плоскости и перемежённая раскладываются иначе.
        assertEquals("разная ширина плоскости яркости", planar.strides[0], p010.strides[0])

        val expected = renderPacked(r, planar, NativeRenderer.Standard.BT2020, false)
        val got = renderPacked(r, p010, NativeRenderer.Standard.BT2020, false)
        assertSameCodes("P010 против планарного 10-битного", got, expected)
    }

    /**
     * Резервный путь загрузки даёт то же, что основной.
     *
     * Замер на Pixel 6 (Mali-G78, драйвер r54p1) перевернул исходное
     * предположение: `GL_EXT_texture_norm16` там НЕТ, и «резервный» путь
     * оказался единственным. Поэтому тест устроен двусторонне и ни при каком
     * исходе не пропускается: если расширение есть — два пути сравниваются
     * между собой, если нет — резерв сверяется с эталоном swscale. Пропуск был бы
     * худшим вариантом: именно на таком GPU резерв и работает всегда.
     *
     * Обратная сторона того же факта: путь [NativeRenderer.UploadPath.NORM16] на
     * Pixel 6 не выполняется вовсе, и заставить его выполниться нельзя — нет
     * расширения. Его проверка возможна только на GPU, где расширение есть.
     *
     * Пути сравниваются между собой, а не с эталоном: они обязаны совпасть с
     * точностью до округления. Байты в текстуре одни и те же, различается лишь
     * способ собрать из них число — `GL_R16` отдаёт его сразу, резерв склеивает
     * два байта в шейдере как `lo/65535 + hi·256/65535`. Во highp float это
     * точно с запасом: 1 LSB 16-битного кода — это 1.5e−5, а погрешность
     * float — 1e−7.
     */
    @Test
    fun fallbackPathMatchesNorm16() {
        val frame = sweep(256, 128, NativeRenderer.PixelFormat.YUV420P10LE)
        val r = offscreen(frame.width, frame.height)
        r.setChromaFilter(false)

        if (!r.hasNorm16()) {
            // Расширения нет — сравнивать не с чем, но и пропускать тест нельзя:
            // тогда на таком GPU резерв остался бы вовсе непроверенным. Сверяем
            // его с эталоном.
            val gl = renderPacked(r, frame, NativeRenderer.Standard.BT709, false)
            assertEquals("без norm16 путь должен быть резервным",
                NativeRenderer.UploadPath.BYTE_PAIR, r.uploadPath())
            val diff = compare10(gl, reference16(frame, NativeRenderer.Standard.BT709, false))
            android.util.Log.i("DddEngine", "нет GL_EXT_texture_norm16, резерв против swscale: $diff")
            assertTrue("резервный путь разошёлся с эталоном: $diff",
                diff.max <= maxTolerance && diff.mean <= meanTolerance)
            return
        }

        r.setForceBytePair(false)
        val viaNorm16 = renderPacked(r, frame, NativeRenderer.Standard.BT709, false)
        assertEquals("ожидался путь norm16", NativeRenderer.UploadPath.NORM16, r.uploadPath())

        r.setForceBytePair(true)
        val viaBytePair = renderPacked(r, frame, NativeRenderer.Standard.BT709, false)
        // Без этой проверки тест сравнивал бы путь norm16 сам с собой и проходил
        // бы, даже если переключатель ничего не делает.
        assertEquals("переключатель не сработал", NativeRenderer.UploadPath.BYTE_PAIR, r.uploadPath())

        val diff = compareGl(viaBytePair, viaNorm16)
        android.util.Log.i("DddEngine", "резерв против norm16: $diff")
        assertTrue("резервный путь разошёлся с norm16: $diff", diff.max <= 1)

        r.setForceBytePair(false)
    }

    /**
     * Stride больше ширины: он в БАЙТАХ, а `GL_UNPACK_ROW_LENGTH` — в текселях.
     *
     * На восьми битах эти единицы совпадают, и ошибка деления не проявляется
     * вовсе; на 16 битах она даёт сдвиг строк вдвое. Для P010 множитель ещё
     * другой — два канала по два байта, четыре байта на тексель, — поэтому
     * проверяются и планарная раскладка, и перемежённая.
     *
     * Stride задан не «чуть больше», а заведомо не кратным ширине: так не
     * выравнивает ни один декодер, и совпадение по случайности исключено.
     */
    @Test
    fun oversizedStride16IsRespected() {
        val w = 100
        val h = 50
        val r = offscreen(w, h)
        r.setChromaFilter(false)

        for (format in listOf(NativeRenderer.PixelFormat.YUV420P10LE,
                              NativeRenderer.PixelFormat.YUV444P12LE)) {
            val frame = sweep(w, h, format, strideY = 320, strideChroma = 320)
            assertTrue("тест бессмысленен без запаса", frame.strides[0] > w * 2)
            val gl = renderPacked(r, frame, NativeRenderer.Standard.BT709, false)
            val diff = compare10(gl, reference16(frame, NativeRenderer.Standard.BT709, false))
            assertTrue("$format: строки съехали, $diff",
                diff.max <= maxTolerance && diff.mean <= meanTolerance)
        }

        // P010 сверяется с планарным кадром тех же кодов и тоже с нештатным
        // stride: перемежённая плоскость — отдельный делитель, и ошибка в нём не
        // видна на планарных форматах.
        val planar = sweep(w, h, NativeRenderer.PixelFormat.YUV420P10LE, strideY = 448,
                           strideChroma = 320)
        val p010 = sweep(w, h, NativeRenderer.PixelFormat.P010, strideY = 320, strideChroma = 448)
        val expected = renderPacked(r, planar, NativeRenderer.Standard.BT709, false)
        val got = renderPacked(r, p010, NativeRenderer.Standard.BT709, false)
        assertSameCodes("P010 с нештатным stride против планарного", got, expected)
    }

    // ───────────────────────────── скорость ─────────────────────────────

    /**
     * 4K 10 бит в бюджет кадра.
     *
     * Замер пессимистичен сразу с трёх сторон, как и на шаге 4: заливка,
     * отрисовка и `glFinish` на каждый кадр, тогда как при воспроизведении
     * заливка следующего кадра идёт параллельно отрисовке предыдущего. Отличие
     * от шага 4 — вдвое больше байт на кадр (24.9 МБ против 12.4) и 10-битная
     * цель вместо 8-битной: именно в неё будет писать тонмаппинг шага 6.
     *
     * Это же и проверка того, что резервного пути с упаковкой на CPU здесь нет:
     * подход 4XVR (`ConvertData_*_10Bit` в `RGB10_A2`) писал бы 33 МБ на кадр
     * процессором, что на 4K заведомо не влезает в 16.67 мс.
     */
    @Test
    fun uhd10BitFitsInFrameBudget() {
        val w = 3840
        val h = 2160
        val r = offscreen(w, h)
        assertTrue("не создалась 10-битная цель 4K", r.setRenderTarget(w, h, true))

        val frames = 30
        val total = r.benchmark(w, h, frames, NativeRenderer.PixelFormat.YUV420P10LE)
        assertTrue("замер не выполнился", total > 0)

        val perFrameMs = total / frames / 1e6
        val report = "4K 10 бит: %.2f мс на кадр (%d кадров, %s, путь %s)".format(
            perFrameMs, frames, r.glInfo(), r.uploadPath())
        android.util.Log.i("DddEngine", report)
        println(report)
        assertTrue("4K 10 бит не влезает в 60 fps — $report", perFrameMs < 16.67)
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private class Case(
        val y: Int,
        val cb: Int,
        val cr: Int,
        val rgb: IntArray,
        val name: String
    )

    private fun offscreen(width: Int, height: Int): NativeRenderer {
        renderer?.release()
        val r = NativeRenderer.offscreen(width, height)
        assertNotNull("не создался offscreen-контекст ${width}x$height", r)
        renderer = r
        return r!!
    }

    /** Кадр в памяти: плоскости, stride-ы и как их трактовать. */
    private class Frame(
        val width: Int,
        val height: Int,
        val format: NativeRenderer.PixelFormat,
        val planes: Array<ByteArray?>,
        val strides: IntArray
    )

    /** Выравнивание строк на 64 байта — как у libavcodec. */
    private fun align64(v: Int) = (v + 63) and 63.inv()

    private fun divUp(v: Int, d: Int) = (v + d - 1) / d

    /**
     * Пишет отсчёт в память в раскладке формата.
     *
     * Little-endian вручную, а не через `ByteBuffer`: кадр обязан быть побайтово
     * одинаковым независимо от порядка байт хоста, иначе эталон и шейдер сойдутся
     * на arm64 и разойдутся на чём-нибудь ещё. Сдвиг [shift] — это и есть
     * выравнивание P010 в старшие разряды.
     */
    private fun put(dst: ByteArray, at: Int, code: Int, bytes: Int, shift: Int) {
        if (bytes == 1) {
            dst[at] = code.toByte()
            return
        }
        val stored = code shl shift
        dst[at] = (stored and 0xff).toByte()
        dst[at + 1] = ((stored ushr 8) and 0xff).toByte()
    }

    /**
     * Собирает кадр по двум функциям: яркость от (x, y) и пара цветности от
     * (cx, cy) в координатах плоскости цветности.
     *
     * Коды задаются в единицах глубины формата; раскладку — планарную,
     * перемежённую, со сдвигом в старшие разряды — берёт на себя эта функция.
     */
    private fun build(
        width: Int,
        height: Int,
        format: NativeRenderer.PixelFormat,
        strideY: Int,
        strideChroma: Int,
        luma: (Int, Int) -> Int,
        chroma: (Int, Int) -> Pair<Int, Int>
    ): Frame {
        val bytes = if (format.isSixteenBit) 2 else 1
        val shift = if (format == NativeRenderer.PixelFormat.P010) 16 - format.bitDepth else 0
        val cw = divUp(width, format.subX)
        val ch = divUp(height, format.subY)

        val sy = if (strideY > 0) strideY else align64(width * bytes)
        val su = if (strideChroma > 0) strideChroma
                 else align64(cw * (if (format.isSemiplanar) 2 else 1) * bytes)
        val sv = if (format.isSemiplanar) 0
                 else if (strideChroma > 0) strideChroma else align64(cw * bytes)

        val y = ByteArray(sy * height)
        for (row in 0 until height) {
            for (col in 0 until width) put(y, row * sy + col * bytes, luma(col, row), bytes, shift)
        }

        val u = ByteArray(su * ch)
        val v = if (format.isSemiplanar) null else ByteArray(sv * ch)
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val (cu, cv) = chroma(col, row)
                if (format.isSemiplanar) {
                    val at = row * su + col * 2 * bytes
                    put(u, at, cu, bytes, shift)
                    put(u, at + bytes, cv, bytes, shift)
                } else {
                    put(u, row * su + col * bytes, cu, bytes, shift)
                    put(v!!, row * sv + col * bytes, cv, bytes, shift)
                }
            }
        }
        return Frame(width, height, format, arrayOf(y, u, v), intArrayOf(sy, su, sv))
    }

    /**
     * Кадр с яркостью, меняющейся и по строкам, и по столбцам, и плитками
     * цветности [tile]×[tile] в координатах ЯРКОСТИ.
     *
     * Плитка задаётся в координатах яркости, поэтому в плоскости цветности её
     * размер делится на прореживание. Считать плитку сразу в chroma-координатах
     * — самая простая ошибка здесь: границы плиток разъедутся с сеткой
     * прореживания, и однородность внутри плитки, на которой держится сравнение
     * с `SWS_POINT`, потеряется.
     *
     * Коды намеренно проходят весь диапазон, включая лежащие вне 64…940: там
     * обе реализации обязаны ограничивать результат одинаково, и это тоже часть
     * совпадения.
     */
    private fun sweep(
        width: Int,
        height: Int,
        format: NativeRenderer.PixelFormat,
        strideY: Int = 0,
        strideChroma: Int = 0
    ): Frame {
        val maxCode = (1 shl format.bitDepth) - 1
        val scale = 1 shl (format.bitDepth - 8)
        val cw = divUp(width, format.subX)
        val chromaTileX = maxOf(1, tile / format.subX)
        val chromaTileY = maxOf(1, tile / format.subY)
        val tilesX = divUp(cw, chromaTileX)

        return build(
            width, height, format, strideY, strideChroma,
            luma = { x, y -> (x * 7 + y * 3) and maxCode },
            chroma = { cx, cy ->
                val index = (cy / chromaTileY) * tilesX + (cx / chromaTileX)
                val u = (index * 16 * scale) and maxCode
                val v = (index * 7 * scale + (maxCode + 1) / 2) and maxCode
                u to v
            }
        )
    }

    /**
     * Градиент яркости от [from] до [to] по X, цветность нейтральная.
     *
     * Деление целочисленное: при `width == to − from + 1` это ровно `from + x`,
     * при другой ширине — равномерная развёртка с повторами. Второе тоже нужно:
     * восьмибитный градиент растягивается на ту же ширину, что десятибитный,
     * иначе сравнивать число уровней было бы нельзя.
     */
    private fun ramp(
        width: Int,
        height: Int,
        format: NativeRenderer.PixelFormat,
        from: Int,
        to: Int
    ): Frame {
        val span = if (width > 1) width - 1 else 1
        val neutral = 1 shl (format.bitDepth - 1)
        return build(
            width, height, format, 0, 0,
            luma = { x, _ -> from + (x * (to - from)) / span },
            chroma = { _, _ -> neutral to neutral }
        )
    }

    /** Однородный кадр YUV420P10LE с заданными Y, Cb, Cr. */
    private fun solid(width: Int, height: Int, y: Int, cb: Int, cr: Int): Frame =
        build(
            width, height, NativeRenderer.PixelFormat.YUV420P10LE, 0, 0,
            luma = { _, _ -> y },
            chroma = { _, _ -> cb to cr }
        )

    /**
     * Заливает кадр, рисует 1:1 в offscreen-цель заданной точности и читает её
     * упакованными словами `2_10_10_10_REV`.
     *
     * STRETCH, а не FIT: при совпадающих пропорциях они дают одно и то же, но
     * STRETCH гарантирует соответствие пиксель-в-пиксель независимо от
     * округления размеров.
     */
    private fun renderPacked(
        r: NativeRenderer,
        frame: Frame,
        standard: NativeRenderer.Standard,
        fullRange: Boolean,
        tenBit: Boolean = true
    ): IntArray {
        assertTrue(
            "цель ${frame.width}x${frame.height} ${if (tenBit) "RGB10_A2" else "RGBA8"}",
            r.setRenderTarget(frame.width, frame.height, tenBit)
        )
        assertTrue(
            "upload ${frame.format}",
            r.upload(
                frame.planes, frame.strides, frame.width, frame.height,
                frame.format, standard, fullRange
            )
        )
        assertTrue("draw", r.draw(0, NativeRenderer.ScaleMode.STRETCH))
        val out = IntArray(frame.width * frame.height)
        assertTrue("readPacked10", r.readPacked10(out))
        return out
    }

    private fun reference16(
        frame: Frame,
        standard: NativeRenderer.Standard,
        fullRange: Boolean
    ): ShortArray {
        val out = ShortArray(frame.width * frame.height * 4)
        assertTrue(
            "эталон swscale не посчитался для ${frame.format}",
            NativeRenderer.reference16(
                frame.planes, frame.strides, frame.width, frame.height,
                frame.format, standard, fullRange, out
            )
        )
        return out
    }

    /** Сколько различных уровней красного выживает в средней строке градиента. */
    private fun gradientLevels(r: NativeRenderer, frame: Frame, tenBit: Boolean): Int {
        val packed = renderPacked(r, frame, NativeRenderer.Standard.BT709, false, tenBit)
        val row = frame.height / 2
        val seen = HashSet<Int>()
        for (x in 0 until frame.width) seen.add(packed[row * frame.width + x] and 0x3ff)
        return seen.size
    }

    /** Градиент обязан не убывать: иначе «много уровней» может означать шум. */
    private fun monotone(r: NativeRenderer, frame: Frame): Boolean {
        val packed = renderPacked(r, frame, NativeRenderer.Standard.BT709, false, true)
        val row = frame.height / 2
        var previous = -1
        for (x in 0 until frame.width) {
            val value = packed[row * frame.width + x] and 0x3ff
            if (value < previous) return false
            previous = value
        }
        // И шкала обязана быть раскрыта целиком: 64 → 0, 940 → 1023.
        val first = packed[row * frame.width] and 0x3ff
        val last = packed[row * frame.width + frame.width - 1] and 0x3ff
        return first <= 2 && last >= 1021
    }

    private class Diff(val max: Int, val mean: Double, val count: Int) {
        override fun toString() = "max=$max mean=%.3f (LSB из 1023, $count px)".format(mean)
    }

    /**
     * Сравнивает 10-битный GL-результат с 16-битным эталоном в LSB
     * десятибитного кода.
     *
     * Эталон приводится к 10 битам, а не GL к 16: разрешение цели — 10 бит, и
     * расширение её значений до 16 создавало бы видимость точности, которой нет.
     * Альфа исключена: шейдер пишет в неё константу, её совпадение ни о чём не
     * говорит, а несовпадение раздувало бы max.
     */
    private fun compare10(gl: IntArray, ref: ShortArray): Diff {
        var max = 0
        var sum = 0L
        for (i in gl.indices) {
            val word = gl[i]
            for (c in 0..2) {
                val got = (word ushr (c * 10)) and 0x3ff
                val ref16 = ref[i * 4 + c].toInt() and 0xffff
                val expected = (ref16 * 1023 + 32767) / 65535
                val d = abs(got - expected)
                if (d > max) max = d
                sum += d
            }
        }
        return Diff(max, sum.toDouble() / (gl.size * 3), gl.size)
    }

    /** Сравнивает два GL-результата между собой, тоже без альфы. */
    private fun compareGl(a: IntArray, b: IntArray): Diff {
        assertEquals("размеры не совпали", b.size, a.size)
        var max = 0
        var sum = 0L
        for (i in a.indices) {
            for (c in 0..2) {
                val d = abs(((a[i] ushr (c * 10)) and 0x3ff) - ((b[i] ushr (c * 10)) and 0x3ff))
                if (d > max) max = d
                sum += d
            }
        }
        return Diff(max, sum.toDouble() / (a.size * 3), a.size)
    }

    /**
     * Требует, чтобы два кадра несли одни и те же коды — с точностью до одного
     * LSB на границе округления.
     *
     * Ровно нулевого расхождения здесь добиться нельзя, и это свойство GL, а не
     * недоделка. Одно и то же число приходит в шейдер разными выражениями:
     * планарный 10-битный кадр даёт `(code/65535)·(65535/1023)`, P010 —
     * `(code·64/65535)·(65535/65472)`. Математически это одно и то же `code/1023`,
     * но ни один из четырёх множителей не представим в двоичном float точно, и
     * произведения расходятся на ~1e−7. На квантовании в 10 бит такая разница
     * незаметна везде, кроме значений, попавших ровно на границу округления, —
     * а синтетический кадр из круглых кодов их и производит в изобилии.
     *
     * Проверку это не ослабляет: тест ловит выравнивание значащих бит, и ошибка
     * там стоит множитель 64, то есть сотни LSB. Среднее при этом равно доле
     * различающихся отсчётов (расхождение всюду 0 или 1), и порог 0.25 не даёт
     * «одному LSB» расползтись по всему кадру.
     */
    private fun assertSameCodes(label: String, got: IntArray, expected: IntArray) {
        val diff = compareGl(got, expected)
        android.util.Log.i("DddEngine", "$label: $diff")
        assertTrue("$label: расхождение больше округления, $diff",
            diff.max <= 1 && diff.mean <= 0.25)
    }

    private fun rangeName(fullRange: Boolean) = if (fullRange) "full range" else "limited range"
}
