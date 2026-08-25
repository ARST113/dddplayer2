package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import top.rootu.dddplayer.engine.EngineIo
import java.io.IOException

/**
 * [EngineIo] поверх цепочки `androidx.media3.datasource.DataSource`.
 *
 * Лежит в пакете `player`, а не `engine`, сознательно: пакет `engine` обязан
 * оставаться свободным от `androidx.media3.*`, иначе удалить Media3-бекенд
 * (шаг 13) будет нельзя. Здесь — единственное место, где два мира встречаются,
 * и после удаления Media3 заменить надо будет только этот файл.
 *
 * Что благодаря этому сохраняется целиком, без переписывания:
 *  - TorrServer: `/cache`-запросы, индикаторы загрузки, ожидание готовности куска;
 *  - `LocalBridgeServer` и локальный HTTP-прокси;
 *  - [ParsingDataSource] с его `TeeDataSource` и разбором метаданных на лету;
 *  - OkHttp: заголовки, cookies, User-Agent, самоподписанные сертификаты, TLS.
 *
 * Позиционирование сделано переоткрытием: у `DataSource` нет seek, и штатный
 * способ перейти на позицию — `close()` + `open(DataSpec.position = N)`, что для
 * HTTP превращается в запрос с `Range`. Это ровно то, что делает сам ExoPlayer
 * при перемотке, так что серверная сторона видит привычную ей нагрузку.
 */
class DataSourceEngineIo private constructor(
    private val dataSource: DataSource,
    private val baseSpec: DataSpec,
    /** Полный размер от начала данных или -1, если неизвестен. */
    private val totalSize: Long
) : EngineIo {

    private var position: Long = baseSpec.position
    private var opened: Boolean = true

    /**
     * Неудача переоткрытия запоминается: после неё источник считается мёртвым.
     *
     * Иначе следующий `read` пошёл бы в закрытый `DataSource`, и вместо честной
     * ошибки перемотки получился бы `IllegalStateException` из глубины media3.
     */
    private var broken: Boolean = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (broken || !opened) return -1
        // IOException не перехватывается: нативная сторона снимет его и вернёт в
        // AVIO код ошибки, а стектрейс останется в logcat через ExceptionDescribe.
        // Проглотить его здесь означало бы отдать демуксеру EOF вместо сбоя сети.
        val n = dataSource.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) return -1
        position += n
        return n
    }

    override fun seekTo(position: Long): Long {
        if (broken) return -1
        if (position == this.position) return position
        if (totalSize >= 0 && position > totalSize) return -1

        return try {
            if (opened) {
                try {
                    dataSource.close()
                } catch (_: IOException) {
                    // Закрытие уже сломанного соединения — не причина не открывать новое.
                }
                opened = false
            }
            dataSource.open(baseSpec.buildUpon().setPosition(position).build())
            opened = true
            this.position = position
            position
        } catch (_: IOException) {
            broken = true
            -1
        }
    }

    override fun size(): Long = totalSize

    /**
     * Перематываемость выводится из известности размера.
     *
     * Размер известен ⇒ сервер отдал `Content-Length` и, значит, отвечает на
     * `Range` — так ведут себя и TorrServer, и локальный файл, и SMB-обёртка.
     * Неизвестный размер — это chunked или live, где переоткрытие с позиции
     * вернёт данные не с той позиции, о которой просили. Соврать «да» здесь хуже,
     * чем сказать «нет»: демуксер получил бы мусор вместо честного отказа.
     */
    override fun seekable(): Boolean = totalSize >= 0

    override fun name(): String = baseSpec.uri.toString()

    override fun close() {
        if (!opened) return
        opened = false
        try {
            dataSource.close()
        } catch (_: IOException) {
        }
    }

    companion object {

        /**
         * Открывает источник и оборачивает его.
         *
         * Открытие делается здесь, а не в native: `DataSource.open` может бросить
         * `IOException` с осмысленным сообщением (404 от TorrServer, отказ TLS), и
         * это сообщение должно дойти до пользователя, а не превратиться в
         * `AVERROR(EIO)`.
         *
         * @param spec может задавать начальную позицию — тогда движок увидит
         *        источник, начинающийся с неё.
         */
        @Throws(IOException::class)
        fun open(dataSource: DataSource, spec: DataSpec): DataSourceEngineIo {
            val length = dataSource.open(spec)
            // open() возвращает длину ОТ ПОЗИЦИИ, а не полный размер: без слагаемого
            // spec.position перемотка в конец файла, открытого со смещением, уехала бы
            // на это смещение.
            val total = if (length == C.LENGTH_UNSET.toLong()) -1L else spec.position + length
            return DataSourceEngineIo(dataSource, spec, total)
        }

        @Throws(IOException::class)
        fun open(factory: DataSource.Factory, uri: Uri): DataSourceEngineIo =
            open(factory.createDataSource(), DataSpec.Builder().setUri(uri).build())
    }
}
