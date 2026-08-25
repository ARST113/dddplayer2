package top.rootu.dddplayer.engine

import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверка шага 4: кадр доходит до поверхности и цвет совпадает с эталоном.
 *
 * Критерий шага — «картинка идентична текущей Media3 по цвету». Сверяться
 * напрямую с Media3 бессмысленно: её GL-путь считает те же матрицы BT.601/709 по
 * тем же формулам, и совпадение двух реализаций одной формулы ничего не
 * доказывает. Поэтому эталон здесь — swscale, независимая реализация с
 * фиксированной точкой, плюс несколько значений, посчитанных по ITU-R вручную.
 * Если шейдер согласуется и с тем, и с другим, он согласуется и с Media3.
 *
 * Кадры генерирует Kotlin и передаёт ОДНИ И ТЕ ЖЕ массивы в GL и в эталон. Если
 * бы кадр генерировали в native для обеих сторон, ошибка генератора вычиталась
 * бы сама из себя, и тест проходил бы на неверных данных.
 *
 * Медиафайлы не нужны: декодера на шаге 4 ещё нет, а синтетический кадр лучше
 * реального — в нём известен каждый байт.
 *
 * Все методы рендерера вызываются из потока теста: EGL-контекст принадлежит
 * одному потоку, и JUnit гарантирует, что тело @Test выполняется в одном.
 */
@RunWith(AndroidJUnit4::class)
class NativeRenderTest {

    /** Сторона плитки цветности в координатах яркости; см. [sweep]. */
    private val tile = 16

    /**
     * Сколько пикселей у границы плитки не сравнивать.
     *
     * При линейном фильтре цветности каждый пиксель — смесь двух соседних
     * отсчётов с весами 0.75/0.25, и внутри однородной плитки смесь равна самой
     * плитке. Расходиться с `SWS_POINT` она может только в пределах одного
     * отсчёта цветности от границы, то есть двух пикселей яркости; 4 взято с
     * запасом.
     */
    private val margin = 4

    /**
     * Допуск против эталона swscale: максимум и среднее в LSB на канал.
     *
     * Расхождение здесь — округление и только оно: шейдер считает во float,
     * swscale — в фиксированной точке по таблицам. Замер на Mali-G78 даёт max 3 и
     * среднее 1.25 на кадре, где отсчёт цветности меняется в каждом текселе.
     *
     * Ослабить допуск нельзя, и это не вкусовое соображение. BT.709 и BT.2020 —
     * близкие матрицы: на 8-битном SDR они расходятся максимум на 16 LSB
     * (см. [wrongStandardIsDetectable]), потому что при больших отсчётах цветности
     * обе уходят в клиппинг раньше, чем разница успевает вырасти. Допуск порядка
     * 8 LSB перестал бы их различать — то есть тест перестал бы проверять, та ли
     * матрица применена.
     */
    private val maxTolerance = 4

    private val meanTolerance = 1.5

    /**
     * Форматы, которые умеет генерировать этот класс: один байт на отсчёт.
     *
     * Перечислены явно, а не через `PixelFormat.entries`: после шага 5 в
     * перечислении лежат ещё десять 16-битных раскладок, и проход по всем
     * значениям молча скормил бы 8-битные байты 10-битному пути.
     */
    private val eightBitFormats = listOf(
        NativeRenderer.PixelFormat.YUV420P,
        NativeRenderer.PixelFormat.NV12,
        NativeRenderer.PixelFormat.NV21
    )

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

    // ───────────────────────────── контекст ─────────────────────────────

    /** Контекст поднимается, и это именно ES 3: на ES 2 нет ни GL_RG8, ни GL_R16. */
    @Test
    fun offscreenContextIsGles3() {
        val r = offscreen(64, 48)
        val info = r.glInfo()
        assertTrue("не похоже на ES 3: '$info'", info.contains("OpenGL ES 3"))
        assertEquals("размер pbuffer", 64 to 48, r.surfaceSize())
    }

    /** Повторное освобождение не должно падать: владелец не обязан помнить состояние. */
    @Test
    fun releaseIsIdempotent() {
        val r = offscreen(16, 16)
        r.release()
        r.release()
        assertTrue(r.isReleased)
        renderer = null
    }

    // ───────────────────────────── цвет ─────────────────────────────

    /**
     * Главная проверка шага: GL-конверсия совпадает с swscale для всех трёх
     * матриц и обоих диапазонов.
     *
     * Фильтр цветности выставлен в «ближайший сосед», и тогда сравнивать можно
     * весь кадр: при текстурных координатах, растянутых на [0,1], отсчёт
     * цветности для пикселя x равен `floor((x+0.5)/W · W/2) = x >> 1` — ровно то
     * размножение, которое делает `SWS_POINT`. Способ интерполяции перестаёт
     * участвовать, и остаётся сравнение одной арифметики с другой.
     */
    @Test
    fun colorMatchesSwscaleForAllStandards() {
        val frame = sweep(256, 128, NativeRenderer.PixelFormat.YUV420P)
        val r = offscreen(frame.width, frame.height)
        r.setChromaFilter(false)

        for (standard in NativeRenderer.Standard.entries) {
            for (fullRange in listOf(false, true)) {
                val diff = renderAndCompare(r, frame, standard, fullRange, margin = 0)
                assertTrue(
                    "$standard ${rangeName(fullRange)}: GL разошёлся со swscale, $diff",
                    diff.max <= maxTolerance && diff.mean <= meanTolerance
                )
            }
        }
    }

    /**
     * Контрольный выстрел в упор: тот же кадр, но эталон посчитан ДРУГОЙ матрицей.
     *
     * Без этой проверки допуск [colorMatchesSwscaleForAllStandards] ничем не
     * ограничен снизу: с достаточно щедрым допуском «совпадает со swscale»
     * выполнялось бы для любой из трёх матриц, и тест доказывал бы только то, что
     * картинка не чёрная.
     *
     * Порог задан не абсолютным числом, а отношением к совпадающему случаю:
     * абсолютное расхождение зависит от кадра (сколько пикселей ушло в клиппинг —
     * там разницы нет вообще), а отношение — нет. Именно этот тест и определяет,
     * насколько тугим обязан быть [maxTolerance].
     */
    @Test
    fun wrongStandardIsDetectable() {
        val frame = sweep(256, 128, NativeRenderer.PixelFormat.YUV420P)
        val r = offscreen(frame.width, frame.height)
        r.setChromaFilter(false)

        val gl = draw(r, frame, NativeRenderer.Standard.BT709, fullRange = false)
        val match = compare(gl, reference(frame, NativeRenderer.Standard.BT709, false),
                            frame.width, frame.height, 0)

        val wrong = mapOf(
            "BT.601" to compare(gl, reference(frame, NativeRenderer.Standard.BT601, false),
                                frame.width, frame.height, 0),
            "BT.2020" to compare(gl, reference(frame, NativeRenderer.Standard.BT2020, false),
                                 frame.width, frame.height, 0),
            "full range" to compare(gl, reference(frame, NativeRenderer.Standard.BT709, true),
                                    frame.width, frame.height, 0)
        )

        for ((name, diff) in wrong) {
            assertTrue(
                "$name не отличается от BT.709 limited: $diff против совпадающего $match",
                diff.max >= 3 * match.max && diff.mean >= 2 * match.mean
            )
            // И отдельно — что различие вообще заметно глазу, а не только в среднем.
            assertTrue("$name расходится меньше чем на 1 % шкалы: $diff", diff.max >= 8)
        }
        // Само совпадение обязано остаться в допуске: иначе отношение выше можно
        // выполнить, испортив обе стороны сразу.
        assertTrue("совпадающий случай вне допуска: $match", match.max <= maxTolerance)
    }

    /**
     * Линейный фильтр цветности не должен менять цвет внутри однородной плитки.
     *
     * Проверка разделяет два независимых свойства: матрица конверсии (её
     * сравнивает [colorMatchesSwscaleForAllStandards]) и интерполятор. Если
     * линейный фильтр расходится с эталоном не только у границ плиток, значит
     * ошибка в выборке — например, полтекселя сдвига в текстурных координатах.
     */
    @Test
    fun linearChromaAffectsOnlyTileEdges() {
        val frame = sweep(256, 128, NativeRenderer.PixelFormat.YUV420P)
        val r = offscreen(frame.width, frame.height)
        r.setChromaFilter(true)

        val diff = renderAndCompare(r, frame, NativeRenderer.Standard.BT709, false, margin)
        assertTrue(
            "внутренности плиток разошлись: $diff",
            diff.max <= maxTolerance && diff.mean <= meanTolerance
        )
        // Отброшенные поля не должны съесть кадр целиком: при tile=16 и margin=4
        // внутренностями остаётся ровно половина по каждой оси, то есть четверть
        // пикселей.
        assertTrue("сравнивать оказалось нечего", diff.count >= frame.width * frame.height / 8)
    }

    /**
     * Значения по ITU-R, посчитанные вручную: чёрный, белый, серый, красный.
     *
     * Это то, что делает утверждение «идентично Media3» проверяемым без Media3:
     * limited-range BT.709 обязан раскрывать 16 в 0 и 235 в 255. Ошибка в
     * раскрытии диапазона — самая частая причина «блёклой» или «выбитой»
     * картинки, и swscale её бы не поймал, если бы ошибка была в обеих
     * реализациях одинаковой.
     */
    @Test
    fun canonicalValuesMatchStandard() {
        val w = 64
        val h = 32
        val r = offscreen(w, h)
        r.setChromaFilter(false)

        val cases = listOf(
            Triple(16, 128 to 128, intArrayOf(0, 0, 0)),          // чёрный
            Triple(235, 128 to 128, intArrayOf(255, 255, 255)),   // белый
            Triple(126, 128 to 128, intArrayOf(128, 128, 128)),   // серый 50 %
            // Красный BT.709 limited: Y=63, Cb=102, Cr=240 → (255, 0, 0).
            Triple(63, 102 to 240, intArrayOf(255, 0, 0))
        )

        for ((y, chroma, expected) in cases) {
            val frame = solid(w, h, y, chroma.first, chroma.second)
            assertTrue(
                "upload",
                r.upload(
                    frame.planes, frame.strides, frame.width, frame.height,
                    frame.format, NativeRenderer.Standard.BT709, false
                )
            )
            assertTrue("draw", r.draw(0, NativeRenderer.ScaleMode.STRETCH))
            val px = readPixel(r, w, h, w / 2, h / 2)
            for (c in 0..2) {
                assertTrue(
                    "Y=$y UV=$chroma канал $c: получено ${px[c]}, ожидалось ${expected[c]}",
                    kotlin.math.abs(px[c] - expected[c]) <= 2
                )
            }
            assertEquals("альфа должна быть непрозрачной", 255, px[3])
        }
    }

    /** NV12 из тех же отсчётов даёт тот же цвет, что планарный YUV420P. */
    @Test
    fun semiplanarMatchesPlanar() {
        val w = 256
        val h = 128
        val planar = sweep(w, h, NativeRenderer.PixelFormat.YUV420P)
        val r = offscreen(w, h)
        r.setChromaFilter(false)
        val expected = draw(r, planar, NativeRenderer.Standard.BT709, false)

        for (format in listOf(NativeRenderer.PixelFormat.NV12, NativeRenderer.PixelFormat.NV21)) {
            val frame = sweep(w, h, format)
            val got = draw(r, frame, NativeRenderer.Standard.BT709, false)
            val diff = compare(got, expected, w, h, 0)
            // Данные те же, путь выборки другой (GL_RG вместо двух GL_R8), так что
            // расхождения быть не может вообще — не «в пределах допуска».
            assertEquals("$format разошёлся с планарным: $diff", 0, diff.max)
        }
    }

    /**
     * Нечётные размеры кадра: у последнего столбца и строки отсчёт цветности
     * один на один пиксель, а не на два.
     *
     * Кадры 1919×1079 приходят с камер и из кривых транскодов регулярно, и
     * округление вниз вместо вверх даёт на них полосу мусора по краю.
     */
    @Test
    fun oddDimensionsRender() {
        val frame = sweep(65, 33, NativeRenderer.PixelFormat.YUV420P)
        val r = offscreen(frame.width, frame.height)
        r.setChromaFilter(false)

        val diff = renderAndCompare(r, frame, NativeRenderer.Standard.BT709, false, margin = 2)
        assertTrue(
            "нечётный кадр разошёлся с эталоном: $diff",
            diff.max <= maxTolerance && diff.mean <= meanTolerance
        )
    }

    /**
     * Stride больше ширины обязателен к учёту — и в яркости, и в цветности.
     *
     * Stride здесь задан вдвое больше ширины: так не выравнивает ни один декодер,
     * и потому ошибка в `GL_UNPACK_ROW_LENGTH` не может замаскироваться
     * случайным совпадением с шириной кадра. Значения меняются и по строкам, и по
     * столбцам: на развёртке [sweep], одинаковой во всех строках, сдвиг строк
     * остался бы незаметен.
     *
     * Для цветности коэффициент другой: `GL_UNPACK_ROW_LENGTH` задаётся в
     * пикселях, а stride — в байтах, и для двухканальной плоскости NV12 это
     * ровно вдвое меньше. Поэтому проверяются оба формата.
     */
    @Test
    fun oversizedStrideIsRespected() {
        val r = offscreen(100, 50)
        r.setChromaFilter(false)

        // Только 8-битные форматы: генератор [varying] пишет по одному байту на
        // отсчёт. 16-битные раскладки проверяет NativeRender10Test — там и stride
        // считается в других единицах.
        for (format in eightBitFormats) {
            val frame = varying(100, 50, format, strideY = 256, strideChroma = 192)
            assertTrue("тест бессмысленен без запаса", frame.strides[0] > frame.width)
            val diff = renderAndCompare(r, frame, NativeRenderer.Standard.BT709, false, margin = 0)
            assertTrue(
                "$format: строки съехали, $diff",
                diff.max <= maxTolerance && diff.mean <= meanTolerance
            )
        }
    }

    // ───────────────────────────── геометрия ─────────────────────────────

    /** FIT добавляет чёрные поля и не растягивает кадр. */
    @Test
    fun fitLetterboxesWideFrame() {
        // Кадр 2:1 в квадратном окне: сверху и снизу по четверти высоты — поля.
        val frame = solid(64, 32, y = 235, u = 128, v = 128)
        val r = offscreen(64, 64)
        assertTrue(r.upload(frame.planes, frame.strides, frame.width, frame.height, frame.format))
        assertTrue(r.draw(0, NativeRenderer.ScaleMode.FIT))

        val pixels = ByteArray(64 * 64 * 4)
        assertTrue(r.readPixels(pixels))

        assertTrue("верхнее поле не чёрное", pixelAt(pixels, 64, 32, 4)[0] < 8)
        assertTrue("нижнее поле не чёрное", pixelAt(pixels, 64, 32, 59)[0] < 8)
        assertTrue("центр чёрный — кадр не нарисован", pixelAt(pixels, 64, 32, 32)[0] > 240)
        // Граница поля должна быть на четверти высоты (2:1 в квадрате), а не где
        // попало: строка 18 — уже кадр.
        assertTrue("кадр начинается не там, где даёт вписывание 2:1",
            pixelAt(pixels, 64, 32, 18)[0] > 240)

        // STRETCH на том же кадре полей не оставляет — иначе «поля» выше могли бы
        // означать просто ненарисованный кадр.
        assertTrue(r.draw(0, NativeRenderer.ScaleMode.STRETCH))
        assertTrue(r.readPixels(pixels))
        assertTrue("STRETCH оставил поля", pixelAt(pixels, 64, 32, 4)[0] > 240)
    }

    /**
     * Поворот на 90° переносит левый верхний угол кадра в правый верхний угол окна.
     *
     * Это поворот изображения по часовой стрелке — то, что означает `rotation`
     * из display matrix контейнера. Ошибка в знаке даёт поворот против часовой,
     * и портретное видео с телефона оказывается вверх ногами набок.
     */
    @Test
    fun rotationTurnsFrameClockwise() {
        val frame = quadrant(64, 64)
        val r = offscreen(64, 64)
        assertTrue(r.upload(frame.planes, frame.strides, frame.width, frame.height, frame.format))

        val corners = mapOf(
            0 to (16 to 16),     // левый верхний
            90 to (48 to 16),    // правый верхний
            180 to (48 to 48),   // правый нижний
            270 to (16 to 48)    // левый нижний
        )
        val pixels = ByteArray(64 * 64 * 4)
        for ((rotation, corner) in corners) {
            assertTrue("draw $rotation", r.draw(rotation, NativeRenderer.ScaleMode.STRETCH))
            assertTrue(r.readPixels(pixels))
            val (x, y) = corner
            assertTrue(
                "поворот $rotation: яркий угол не в ($x,$y)",
                pixelAt(pixels, 64, x, y)[0] > 200
            )
            // И симметрично: в противоположном углу должно быть темно, иначе
            // «яркий угол на месте» выполнялось бы и на белом кадре.
            assertTrue(
                "поворот $rotation: кадр весь яркий",
                pixelAt(pixels, 64, 64 - x, 64 - y)[0] < 60
            )
        }
    }

    /** Анаморфный SAR расширяет кадр: 2.0 из 1:1 делает 2:1. */
    @Test
    fun pixelAspectRatioWidensFrame() {
        val frame = solid(64, 64, y = 235, u = 128, v = 128)
        val r = offscreen(64, 64)
        r.setPixelAspectRatio(2f)
        assertTrue(r.upload(frame.planes, frame.strides, frame.width, frame.height, frame.format))
        assertTrue(r.draw(0, NativeRenderer.ScaleMode.FIT))

        val pixels = ByteArray(64 * 64 * 4)
        assertTrue(r.readPixels(pixels))
        // Квадратный кадр с SAR 2:1 в квадратном окне вписывается как 2:1 —
        // значит появились поля сверху и снизу, которых при SAR 1:1 не было.
        assertTrue("SAR не применился: поля не появились", pixelAt(pixels, 64, 32, 4)[0] < 8)
        assertTrue("кадр не нарисован", pixelAt(pixels, 64, 32, 32)[0] > 240)
    }

    // ───────────────────────────── окно ─────────────────────────────

    /**
     * Оконный путь: `Surface` → `eglCreateWindowSurface` → `eglSwapBuffers`.
     *
     * Pbuffer из остальных тестов проверяет шейдер, но не проверяет самое хрупкое
     * в EGL — совместимость конфига с форматом буферов окна (`EGL_BAD_MATCH`) и
     * то, что кадр вообще попадает наружу. `ImageReader` даёт настоящий
     * `ANativeWindow` и при этом позволяет прочитать, что в него отдали;
     * `SurfaceView` второго не позволяет.
     */
    @Test
    fun windowSurfaceDeliversFrame() {
        val w = 128
        val h = 64
        val reader = newReader(w, h)
        try {
            val r = NativeRenderer.forSurface(reader.surface)
            assertNotNull("не создался оконный контекст на ImageReader", r)
            renderer = r
            assertEquals("размер окна", w to h, r!!.surfaceSize())

            val frame = solid(w, h, y = 126, u = 128, v = 128)
            assertTrue(r.upload(frame.planes, frame.strides, w, h, frame.format))
            assertTrue(r.draw(0, NativeRenderer.ScaleMode.STRETCH))
            assertTrue("eglSwapBuffers не прошёл", r.swap())

            val image = acquire(reader, 3000)
            assertNotNull("кадр не дошёл до ImageReader", image)
            try {
                val plane = image!!.planes[0]
                val buffer = plane.buffer
                val offset = (h / 2) * plane.rowStride + (w / 2) * plane.pixelStride
                val red = buffer.get(offset).toInt() and 0xff
                // Серый 50 % по BT.709 limited: (126-16)/219·255 ≈ 128.
                assertTrue("в окне не наш кадр: R=$red", kotlin.math.abs(red - 128) <= 3)
            } finally {
                image?.close()
            }
        } finally {
            renderer?.release()
            renderer = null
            reader.close()
        }
    }

    // ───────────────────────────── скорость ─────────────────────────────

    /**
     * 4K не должен просаживаться — вторая половина критерия шага.
     *
     * Измеряется заливка текстур плюс отрисовка плюс `glFinish` на каждый кадр,
     * то есть заведомо пессимистично сразу с трёх сторон: в воспроизведении
     * заливка следующего кадра идёт параллельно отрисовке предыдущего, вывод
     * почти никогда не бывает 4K (на телефоне это 1080p, на гарнитуре ~2K на
     * глаз), а `glFinish` запрещает драйверу перекрывать кадры. Поверхность здесь
     * тем не менее 3840×2160: считать пиксели надо по худшему случаю.
     *
     * Кадр генерируется в native, чтобы в замер не попало копирование массивов
     * через JNI.
     *
     * Порог 16.67 мс — бюджет 60 fps. Видео 4K бывает и 24, и 30 fps, но брать на
     * вывод больше кадрового интервала при 60 Гц нельзя: на разнице копится
     * дрожание, а на шаге 6 сюда добавится тонмаппинг.
     *
     * Декодирование 4K к этому замеру не относится — это шаг 5.
     */
    @Test
    fun uhdFrameFitsInFrameBudget() {
        val r = offscreen(3840, 2160)
        val frames = 30
        val total = r.benchmark(3840, 2160, frames)
        assertTrue("замер не выполнился", total > 0)

        val perFrameMs = total / frames / 1e6
        val report = "4K вывод: %.2f мс на кадр (%d кадров, %s)".format(perFrameMs, frames, r.glInfo())
        android.util.Log.i("DddEngine", report)
        println(report)
        assertTrue("4K вывод не влезает в 60 fps — $report", perFrameMs < 16.67)
    }

    // ───────────────────────────── helpers ─────────────────────────────

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

    private fun chromaSize(v: Int) = (v + 1) / 2

    /**
     * Кадр с развёрткой яркости по X и плитками цветности [tile]×[tile].
     *
     * Плитка задаётся в координатах ЯРКОСТИ, поэтому в плоскости цветности её
     * размер вдвое меньше. Считать плитку сразу в chroma-координатах — самая
     * простая ошибка здесь: границы плиток разъедутся с сеткой 2×2, внутренности
     * перестанут быть однородными, и допуск [margin] потеряет смысл.
     */
    private fun sweep(width: Int, height: Int, format: NativeRenderer.PixelFormat): Frame {
        val cw = chromaSize(width)
        val ch = chromaSize(height)
        val semiplanar = format != NativeRenderer.PixelFormat.YUV420P

        val sy = align64(width)
        val su = if (semiplanar) align64(cw * 2) else align64(cw)
        val sv = if (semiplanar) 0 else align64(cw)

        val y = ByteArray(sy * height)
        for (row in 0 until height) {
            for (col in 0 until width) y[row * sy + col] = (col and 0xff).toByte()
        }

        val u = ByteArray(su * ch)
        val v = if (semiplanar) null else ByteArray(sv * ch)
        val chromaTile = maxOf(1, tile / 2)
        val tilesX = (cw + chromaTile - 1) / chromaTile

        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val index = (row / chromaTile) * tilesX + (col / chromaTile)
                val cu = ((index * 16) and 0xff).toByte()
                val cv = ((index * 7 + 128) and 0xff).toByte()
                if (semiplanar) {
                    val at = row * su + col * 2
                    // NV21 — тот же кадр с другим порядком байт: трактовку задаёт
                    // формат, поэтому здесь пишется «первый, второй».
                    if (format == NativeRenderer.PixelFormat.NV21) {
                        u[at] = cv; u[at + 1] = cu
                    } else {
                        u[at] = cu; u[at + 1] = cv
                    }
                } else {
                    u[row * su + col] = cu
                    v!![row * sv + col] = cv
                }
            }
        }
        return Frame(width, height, format, arrayOf(y, u, v), intArrayOf(sy, su, sv))
    }

    /** Однородный кадр YUV420P с заданными Y, U, V. */
    private fun solid(width: Int, height: Int, y: Int, u: Int, v: Int): Frame {
        val cw = chromaSize(width)
        val ch = chromaSize(height)
        val sy = align64(width)
        val sc = align64(cw)
        return Frame(
            width, height, NativeRenderer.PixelFormat.YUV420P,
            arrayOf(
                ByteArray(sy * height) { y.toByte() },
                ByteArray(sc * ch) { u.toByte() },
                ByteArray(sc * ch) { v.toByte() }
            ),
            intArrayOf(sy, sc, sc)
        )
    }

    /** Кадр с ярким левым верхним квадрантом: маркер ориентации. */
    private fun quadrant(width: Int, height: Int): Frame {
        val cw = chromaSize(width)
        val ch = chromaSize(height)
        val sy = align64(width)
        val sc = align64(cw)
        val y = ByteArray(sy * height) { 16.toByte() }
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) y[row * sy + col] = 235.toByte()
        }
        return Frame(
            width, height, NativeRenderer.PixelFormat.YUV420P,
            arrayOf(y, ByteArray(sc * ch) { 128.toByte() }, ByteArray(sc * ch) { 128.toByte() }),
            intArrayOf(sy, sc, sc)
        )
    }

    /**
     * Кадр с заданными вручную stride-ами, где каждый отсчёт зависит и от строки,
     * и от столбца. Для проверки распаковки, а не цвета.
     */
    private fun varying(
        width: Int,
        height: Int,
        format: NativeRenderer.PixelFormat,
        strideY: Int,
        strideChroma: Int
    ): Frame {
        val cw = chromaSize(width)
        val ch = chromaSize(height)
        val semiplanar = format != NativeRenderer.PixelFormat.YUV420P

        val y = ByteArray(strideY * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                y[row * strideY + col] = ((row * 7 + col * 3) and 0xff).toByte()
            }
        }
        val u = ByteArray(strideChroma * ch)
        val v = if (semiplanar) null else ByteArray(strideChroma * ch)
        for (row in 0 until ch) {
            for (col in 0 until cw) {
                val cu = ((row * 11 + col * 5) and 0xff).toByte()
                val cv = ((row * 3 + col * 13 + 64) and 0xff).toByte()
                if (semiplanar) {
                    val at = row * strideChroma + col * 2
                    if (format == NativeRenderer.PixelFormat.NV21) {
                        u[at] = cv; u[at + 1] = cu
                    } else {
                        u[at] = cu; u[at + 1] = cv
                    }
                } else {
                    u[row * strideChroma + col] = cu
                    v!![row * strideChroma + col] = cv
                }
            }
        }
        return Frame(
            width, height, format, arrayOf(y, u, v),
            intArrayOf(strideY, strideChroma, if (semiplanar) 0 else strideChroma)
        )
    }

    /** Заливает кадр, рисует 1:1 и возвращает RGBA поверхности. */
    private fun draw(
        r: NativeRenderer,
        frame: Frame,
        standard: NativeRenderer.Standard,
        fullRange: Boolean
    ): ByteArray {
        assertTrue(
            "upload ${frame.format}",
            r.upload(
                frame.planes, frame.strides, frame.width, frame.height,
                frame.format, standard, fullRange
            )
        )
        // STRETCH, а не FIT: при совпадающих пропорциях они дают одно и то же, но
        // STRETCH гарантирует соответствие пиксель-в-пиксель независимо от того,
        // как округлился размер поверхности.
        assertTrue("draw", r.draw(0, NativeRenderer.ScaleMode.STRETCH))
        val out = ByteArray(frame.width * frame.height * 4)
        assertTrue("readPixels", r.readPixels(out))
        return out
    }

    private fun reference(
        frame: Frame,
        standard: NativeRenderer.Standard,
        fullRange: Boolean
    ): ByteArray {
        val out = ByteArray(frame.width * frame.height * 4)
        assertTrue(
            "эталон swscale не посчитался",
            NativeRenderer.reference(
                frame.planes, frame.strides, frame.width, frame.height,
                frame.format, standard, fullRange, out
            )
        )
        return out
    }

    private fun renderAndCompare(
        r: NativeRenderer,
        frame: Frame,
        standard: NativeRenderer.Standard,
        fullRange: Boolean,
        margin: Int
    ): Diff {
        val gl = draw(r, frame, standard, fullRange)
        val ref = reference(frame, standard, fullRange)
        return compare(gl, ref, frame.width, frame.height, margin)
    }

    private class Diff(val max: Int, val mean: Double, val count: Int) {
        override fun toString() = "max=$max mean=%.3f по $count пикселям".format(mean)
    }

    /**
     * Сравнивает RGB (без альфы) по пикселям, отстоящим от границы плитки
     * цветности не меньше чем на [margin].
     *
     * Альфа исключена намеренно: шейдер пишет в неё константу, и её совпадение
     * не свидетельствует ни о чём — а вот несовпадение раздувало бы max.
     */
    private fun compare(a: ByteArray, b: ByteArray, width: Int, height: Int, margin: Int): Diff {
        var max = 0
        var sum = 0L
        var count = 0
        for (y in 0 until height) {
            if (margin > 0 && !interior(y, height, margin)) continue
            for (x in 0 until width) {
                if (margin > 0 && !interior(x, width, margin)) continue
                val at = (y * width + x) * 4
                for (c in 0..2) {
                    val d = kotlin.math.abs((a[at + c].toInt() and 0xff) - (b[at + c].toInt() and 0xff))
                    if (d > max) max = d
                    sum += d
                }
                count++
            }
        }
        assertTrue("нечего сравнивать: margin=$margin при ${width}x$height", count > 0)
        return Diff(max, sum.toDouble() / (count * 3), count)
    }

    /** Внутри плитки, не ближе [margin] к её границе и к границе кадра. */
    private fun interior(coord: Int, extent: Int, margin: Int): Boolean {
        if (coord < margin || coord >= extent - margin) return false
        val within = coord % tile
        return within >= margin && within < tile - margin
    }

    private fun pixelAt(rgba: ByteArray, width: Int, x: Int, y: Int): IntArray {
        val at = (y * width + x) * 4
        return IntArray(4) { rgba[at + it].toInt() and 0xff }
    }

    private fun readPixel(r: NativeRenderer, width: Int, height: Int, x: Int, y: Int): IntArray {
        val out = ByteArray(width * height * 4)
        assertTrue("readPixels", r.readPixels(out))
        return pixelAt(out, width, x, y)
    }

    /**
     * `ImageReader`, в который может рисовать GPU и читать CPU.
     *
     * С API 29 флаги использования буфера задаются явно: без
     * `USAGE_GPU_COLOR_OUTPUT` часть драйверов отвечает на
     * `eglCreateWindowSurface` ошибкой `EGL_BAD_MATCH`, потому что буфер выделен
     * только под чтение процессором. На более старых системах остаётся
     * четырёхаргументный конструктор с флагами по умолчанию.
     */
    private fun newReader(width: Int, height: Int): ImageReader {
        val format = android.graphics.PixelFormat.RGBA_8888
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(
                width, height, format, 2,
                android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or
                    android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN
            )
        } else {
            ImageReader.newInstance(width, height, format, 2)
        }
    }

    /**
     * Ждёт кадр от `ImageReader` опросом, а не колбэком.
     *
     * Колбэк требует своего `Handler`-потока, а тест и так однопоточный из-за
     * EGL; опрос здесь честнее и короче.
     */
    private fun acquire(reader: ImageReader, timeoutMs: Long): android.media.Image? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            reader.acquireLatestImage()?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    private fun rangeName(fullRange: Boolean) = if (fullRange) "full range" else "limited range"
}
