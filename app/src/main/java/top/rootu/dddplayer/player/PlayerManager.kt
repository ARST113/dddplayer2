package top.rootu.dddplayer.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.net.Uri
import android.util.Log
import android.view.SurfaceHolder
import android.view.accessibility.CaptioningManager
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import okhttp3.OkHttpClient
import top.rootu.dddplayer.App.Companion.USER_AGENT
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AudioMixerLogic
import top.rootu.dddplayer.logic.UnifiedMetadataReader
import top.rootu.dddplayer.model.MediaItem
import top.rootu.dddplayer.utils.MediaFormatHelper
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import androidx.media3.common.MediaItem as Media3MediaItem

class PlayerManager(
    private val context: Context,
    listener: Player.Listener
) {
    private val backendTag = "DDDPlayer/Backend"
    private val vlcTag = "DDDPlayer/VLC"

    private var playerListener: Player.Listener? = listener
    private val appContext = context.applicationContext
    private val settingsRepo = SettingsRepository.getInstance(appContext)

    var exoPlayer: ExoPlayer? = null
        private set

    private var mediaSession: MediaSession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    // Состояние для восстановления
    private var currentWindowIndex = 0
    private var currentPosition = 0L
    private var currentMediaItems: List<Media3MediaItem> = emptyList()
    private var playWhenReady = true

    private var currentTrackInfo: Map<Int, UnifiedMetadataReader.TrackInfo> = emptyMap()
    private var currentPlaylistItems: List<MediaItem> = emptyList()
    private var boundSurfaceHolder: SurfaceHolder? = null
    private var activeBackend: PlaybackBackend? = null
    private var media3Backend: Media3Backend? = null
    private var vlcBackend: VlcBackend? = null
    var onMetadataAvailable: (() -> Unit)? = null
    var onPlayerCreated: ((ExoPlayer) -> Unit)? = null
    var onVideoFormatChanged: ((Format) -> Unit)? = null
    var onAudioOutputFormatChanged: ((String) -> Unit)? = null
    var onBackendPlayingChanged: ((Boolean) -> Unit)? = null
    var onBackendBufferingChanged: ((Boolean) -> Unit)? = null
    var onBackendEnded: (() -> Unit)? = null
    var onBackendError: ((Throwable) -> Unit)? = null

    private val resolvedMediaTypes = ConcurrentHashMap<String, String>()

    private val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
    object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("TLS")
    private fun socketFactory(): SSLSocketFactory {
        sslContext.init(null, trustAllCerts, SecureRandom())
        return sslContext.socketFactory
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        // Разрешаем самоподписанные сертификаты (для пользовательских серверов с видео)
        .sslSocketFactory(socketFactory(), trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        // Запишим правильный MimeType от сервера, чтобы перезапустить видео при ошибке контейнера
        // Используем Application Interceptor, чтобы поймать оригинальный URL
        .addInterceptor { chain ->
            val request = chain.request()
            val originalUrl = request.url.toString()

            // Выполняем запрос (включая все редиректы)
            val response = chain.proceed(request)

            // Получаем заголовки и финальный URL (после редиректов)
            val contentType = response.header("Content-Type")
            val finalUrl = response.request.url.toString()

            var exoMimeType: String? = null

            // Пытаемся определить по Content-Type
            if (contentType != null) {
                val lowerType = contentType.lowercase()
                exoMimeType = when (lowerType) {
                    "application/x-mpegurl",
                    "application/vnd.apple.mpegurl" -> MimeTypes.APPLICATION_M3U8
                    "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                    "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS
                    else -> null
                }
            }

            // Если Content-Type кривой (например octet-stream), смотрим на расширение финального URL
            if (exoMimeType == null) {
                val extension = MediaFormatHelper.getFileExtension(finalUrl.toUri().path ?: "")
                exoMimeType = when (extension) {
                    "m3u8" -> MimeTypes.APPLICATION_M3U8
                    "mpd" -> MimeTypes.APPLICATION_MPD
                    "ism", "isml" -> MimeTypes.APPLICATION_SS
                    else -> null
                }
            }

            // Сохраняем найденный тип
            if (exoMimeType != null) {
                resolvedMediaTypes[originalUrl] = exoMimeType
            }

            response
        }
        .build()

    // Фабрика для HTTP (сеть)
    private val baseHttpFactory = OkHttpDataSource.Factory(okHttpClient)
        .setUserAgent(USER_AGENT)

    // Универсальная фабрика, которая умеет работать с content://, file:// и http://
    // Мы передаем baseHttpFactory как источник для сетевых запросов.
    private val defaultDataSourceFactory = DefaultDataSource.Factory(appContext, baseHttpFactory)

    // Пересоздаем фабрику при инициализации, чтобы гарантировать чистое состояние
    private fun createParsingDataSourceFactory(): ParsingDataSourceFactory {
        return ParsingDataSourceFactory(
            upstreamFactory = defaultDataSourceFactory,
            onMetadataParsed = { metadataMap ->
                currentTrackInfo = metadataMap
                onMetadataAvailable?.invoke()
            },
            isMetadataParsed = { currentTrackInfo.isNotEmpty() }
        )
    }

    private val tsExtractorFlags = DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
            DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM

    private val extractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(tsExtractorFlags)
        .setTsExtractorTimestampSearchBytes(5000 * TsExtractor.TS_PACKET_SIZE)
        .setMp4ExtractorFlags(
            Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES or
                    Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES_H265
        )
        // Включаем поиск метаданных в начале каждого чанка для MKV
        .setMatroskaExtractorFlags(0)
        .setConstantBitrateSeekingEnabled(true)

    private val loadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy() {
        override fun getMinimumLoadableRetryCount(dataType: Int): Int = 5
    }

    fun initializePlayer() {
        if (exoPlayer != null) {
            releasePlayer(isFinalRelease = false, saveState = true)
        }

        // === Кастомный MediaCodecSelector для Dolby Vision Fallback ===
        val mediaCodecSelector = if (settingsRepo.isMapDvToHevcEnabled()) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                var finalMimeType = mimeType

                // Если плеер запрашивает декодер для Dolby Vision...
                if (MimeTypes.VIDEO_DOLBY_VISION == mimeType) {
                    // ...мы "обманываем" его и говорим системе искать декодер для HEVC.
                    Log.i("PlayerManager", "DV Fallback: Intercepted DV request, substituting with HEVC.")
                    finalMimeType = MimeTypes.VIDEO_H265
                }

                // Запрашиваем у системы декодеры для (возможно) подмененного MIME-типа.
                try {
                    MediaCodecUtil.getDecoderInfos(
                        finalMimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                } catch (e: MediaCodecUtil.DecoderQueryException) {
                    Log.e("PlayerManager", "Failed to query decoders for $finalMimeType", e)
                    emptyList()
                }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        val trackSelector = DefaultTrackSelector(appContext)
        val parametersBuilder = trackSelector.buildUponParameters()
            .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
            .setTunnelingEnabled(settingsRepo.isTunnelingEnabled())
            // Разрешаем плееру игнорировать битые дорожки
            .setExceedRendererCapabilitiesIfNecessary(true)
            .setAllowMultipleAdaptiveSelections(true)

        // Audio Language
        val audioPref = settingsRepo.getPreferredAudioLang()
        when (audioPref) {
            SettingsRepository.TRACK_DEFAULT -> parametersBuilder.setPreferredAudioLanguages()
            SettingsRepository.TRACK_DEVICE -> parametersBuilder.setPreferredAudioLanguages(*getDeviceLanguages())
            else -> parametersBuilder.setPreferredAudioLanguage(audioPref)
        }

        // Subtitle Language & CaptioningManager
        val captioningManager = appContext.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        if (captioningManager == null || !captioningManager.isEnabled) {
            parametersBuilder.setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        }

        val subPref = settingsRepo.getPreferredSubLang()
        if (subPref == SettingsRepository.TRACK_DEVICE && captioningManager?.locale != null) {
            parametersBuilder.setPreferredTextLanguage(captioningManager.locale?.toLanguageTag())
        } else {
            when (subPref) {
                SettingsRepository.TRACK_DEFAULT -> parametersBuilder.setPreferredTextLanguages()
                SettingsRepository.TRACK_DEVICE -> parametersBuilder.setPreferredTextLanguages(*getDeviceLanguages())
                else -> parametersBuilder.setPreferredTextLanguage(subPref)
            }
        }

        trackSelector.setParameters(parametersBuilder)

        val renderersFactory = object : DefaultRenderersFactory(appContext) {
            init {
                setMediaCodecSelector(mediaCodecSelector)
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink, // <-- Стандартный Sink от ExoPlayer
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                // Решаем, какой Sink использовать
                val finalSink = if (settingsRepo.isStereoDownmixEnabled()) {
                    // Если нужен Downmix -> создаем свой Sink с процессором

                    // Ограничиваем аудиобуффер, чтобы не упасть по памяти
                    val bufferSizeProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
                            minSize, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, speed ->

                        // Получаем стандартный размер, рассчитанный ExoPlayer
                        val standardSize = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
                            .getBufferSizeInBytes(
                                minSize,
                                encoding,
                                outputMode,
                                pcmFrameSize,
                                sampleRate,
                                bitrate,
                                speed
                            )

                        standardSize.coerceAtMost(256 * 1024) // 256КБ должно хватить
                    }

                    val audioOutputProvider = AudioTrackAudioOutputProvider.Builder(appContext)
                        .setAudioTrackBufferSizeProvider(bufferSizeProvider)
                        .build()
                    //~ Ограничиваем аудиобуффер, чтобы не упасть по памяти

                    val sinkBuilder = DefaultAudioSink.Builder(appContext)
                        .setEnableAudioOutputPlaybackParameters(true)
                        .setEnableFloatOutput(false) // Важно для стабильности Downmix на старых чипах
                        .setAudioOutputProvider(audioOutputProvider) // Ограничиваем аудиобуффер, чтобы не упасть по памяти

                    val mixingProcessor = ChannelMixingAudioProcessor()
                    val matrices = AudioMixerLogic.createMatrices(settingsRepo)
                    matrices.forEach { matrix ->
                        mixingProcessor.putChannelMixingMatrix(matrix)
                    }

                    sinkBuilder.setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(mixingProcessor)
                    )

                    sinkBuilder.build()
                } else {
                    // Если Downmix не нужен -> используем стандартный (для Passthrough и т.д.)
                    audioSink
                }

                super.buildAudioRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    finalSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }
        }.apply {
            setExtensionRendererMode(settingsRepo.getDecoderPriority())
            setEnableDecoderFallback(true) // Разрешаем софтовый декодер
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000, // minBufferMs
                50000, // maxBufferMs
                500,   // bufferForPlaybackMs
                5000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Build Player
        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext, extractorsFactory)
                    .setDataSourceFactory(createParsingDataSourceFactory())
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            )
            .build()

        // Audio Attributes
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        // Skip Silence
        if (settingsRepo.isSkipSilenceEnabled()) {
            player.skipSilenceEnabled = true
        }

        // Handle Noisy
        player.setHandleAudioBecomingNoisy(true)

        // Seek Parameters
        player.setSeekBackIncrementMs(15000)
        player.setSeekForwardIncrementMs(15000)

        // --- LoudnessEnhancer ---
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initLoudnessEnhancer(audioSessionId)
            }
        })
        if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            initLoudnessEnhancer(player.audioSessionId)
        }

        playerListener?.let { player.addListener(it) }

        // --- MediaSession ---
        if (player.canAdvertiseSession()) {
            try {
                mediaSession = MediaSession.Builder(context, player).build()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
            ) {
                onVideoFormatChanged?.invoke(format)
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                config: AudioSink.AudioTrackConfig
            ) {
                // config.encoding говорит нам, в каком формате данные идут на железо.
                // Если это PCM (16-bit, Float), значит плеер декодировал звук.
                // Если это AC3, DTS и т.д., значит работает Passthrough.
                val encodingName = MediaFormatHelper.getAudioCodecName(config.encoding)
                val channelStr = MediaFormatHelper.getChannelConfigString(config.channelConfig)
                val passthrough = if (config.tunneling) " ↳" else ""
                val info = "$encodingName $channelStr$passthrough"

                onAudioOutputFormatChanged?.invoke(info)
            }
        })

        this.exoPlayer = player
        media3Backend = Media3Backend(player)
        activeBackend = media3Backend
        boundSurfaceHolder?.let { media3Backend?.attachSurfaceHolder(it) }
        Log.i(backendTag, "Active backend=MEDIA3")

        // 6. Restore State
        if (currentMediaItems.isNotEmpty()) {
            val sources = buildMediaSources(currentMediaItems)
            player.setMediaSources(sources, currentWindowIndex, currentPosition)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        onPlayerCreated?.invoke(player)
    }

    private fun buildMediaSources(exoItems: List<Media3MediaItem>): List<MediaSource> {
        // Передаем false вторым параметром. Это заставит плеер скачать первый .ts файл
        // и проанализировать его структуру, вместо того чтобы гадать по пустому m3u8.
        val hlsExtractorFactory = DefaultHlsExtractorFactory(tsExtractorFlags, false)

        val hlsMediaSourceFactory = HlsMediaSource.Factory(createParsingDataSourceFactory())
            .setExtractorFactory(hlsExtractorFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        val defaultMediaSourceFactory = DefaultMediaSourceFactory(appContext, extractorsFactory)
            .setDataSourceFactory(createParsingDataSourceFactory())
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

        return exoItems.map { exoItem ->
            val uriStr = exoItem.localConfiguration?.uri?.toString() ?: ""
            val mimeType = resolvedMediaTypes[uriStr]
                ?: exoItem.localConfiguration?.mimeType
                ?: MediaFormatHelper.getVideoMimeType(uriStr.toUri())
            val isHls = mimeType == MimeTypes.APPLICATION_M3U8

            if (isHls) {
                hlsMediaSourceFactory.createMediaSource(exoItem)
            } else {
                defaultMediaSourceFactory.createMediaSource(exoItem)
            }
        }
    }

    private fun initLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer?.release()
            val boost = settingsRepo.getLoudnessBoost()
            if (boost > 0) {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                loudnessEnhancer?.setTargetGain(boost)
                loudnessEnhancer?.enabled = true
            } else {
                loudnessEnhancer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateTrackSelectionParameters() {
        val player = exoPlayer ?: return
        val audioPref = settingsRepo.getPreferredAudioLang()
        val subPref = settingsRepo.getPreferredSubLang()

        val builder = player.trackSelectionParameters.buildUpon()

        when (audioPref) {
            SettingsRepository.TRACK_DEFAULT -> builder.setPreferredAudioLanguages()
            SettingsRepository.TRACK_DEVICE -> builder.setPreferredAudioLanguages(*getDeviceLanguages())
            else -> builder.setPreferredAudioLanguage(audioPref)
        }

        when (subPref) {
            SettingsRepository.TRACK_DEFAULT -> builder.setPreferredTextLanguages()
            SettingsRepository.TRACK_DEVICE -> builder.setPreferredTextLanguages(*getDeviceLanguages())
            else -> builder.setPreferredTextLanguage(subPref)
        }

        player.trackSelectionParameters = builder.build()
    }

    private fun getDeviceLanguages(): Array<String> {
        val locales = LocaleListCompat.getAdjustedDefault()
        val languages = mutableListOf<String>()
        for (i in 0 until locales.size()) {
            locales.get(i)?.language?.let { languages.add(it) }
        }
        return languages.toTypedArray()
    }

    fun getResolvedMimeType(uri: android.net.Uri): String? {
        return resolvedMediaTypes[uri.toString()]
    }

    fun loadPlaylist(items: List<MediaItem>, startIndex: Int, startPosMs: Long = 0) {
        currentPlaylistItems = items
        Log.i(backendTag, "Selected playback engine=${settingsRepo.getPlaybackEngine()}")
        currentTrackInfo = emptyMap()
        resolvedMediaTypes.clear() // Очищаем кэш типов при новой загрузке

        if (items.isNotEmpty()) {
            baseHttpFactory.setDefaultRequestProperties(items[startIndex].headers)
        }

        val exoItems = items.map { item ->
            val subConfigs = item.subtitles.map { sub ->
                Media3MediaItem.SubtitleConfiguration.Builder(sub.uri)
                    .setMimeType(sub.mimeType)
                    .setLanguage("ext")
                    .setLabel(sub.name ?: sub.filename)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtworkUri(item.posterUri)
                .build()

            // Даем плееру больше времени на "переваривание" битых кадров,
            // отодвигая точку воспроизведения дальше от края трансляции.
            val liveConfig = Media3MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(15000) // увеличили до 15 сек
                .setMaxOffsetMs(30000)    // Максимальное отставание
                .setMaxPlaybackSpeed(1.05f) // Разрешаем ускоряться до 1.05x чтобы догнать поток
                .build()

            Media3MediaItem.Builder()
                .setUri(item.uri)
//                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .setSubtitleConfigurations(subConfigs)
                .setLiveConfiguration(liveConfig)
                .build()
        }

        currentMediaItems = exoItems
        currentWindowIndex = startIndex
        currentPosition = if (startPosMs <= 0L) C.TIME_UNSET else startPosMs
        playWhenReady = true

        if (settingsRepo.getPlaybackEngine() == SettingsRepository.PLAYBACK_ENGINE_VLC_ONLY) {
            if (activeBackend === media3Backend) {
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                media3Backend = null
                mediaSession?.release()
                mediaSession = null
            }
            // TODO(MVP): controller-driven next/previous for VLC_ONLY should reopen item via PlayerManager/loadPlaylist.
            // TODO(MVP): VLC track UI/subtitle slave support not implemented yet.
            // TODO(MVP): persist contentKey -> preferred backend (AUTO) after successful fallback.
            val selected = items.getOrNull(startIndex) ?: return
            val backend = vlcBackend ?: VlcBackend(appContext, settingsRepo).also { vlcBackend = it }
            backend.attachSurfaceHolder(boundSurfaceHolder)
            attachVlcListener(backend)
            Log.i(vlcTag, "VLC prepare uri=${selected.uri}")
            backend.prepare(selected.uri, selected.headers, startPosMs)
            activeBackend = backend
            Log.i(backendTag, "Active backend=VLC")
            return
        }
        if (exoPlayer == null) {
            initializePlayer()
        } else {
            val sources = buildMediaSources(exoItems)
            exoPlayer?.setMediaSources(sources, startIndex, currentPosition)
            exoPlayer?.playWhenReady = true
            exoPlayer?.prepare()
        }
    }

    fun releasePlayer(isFinalRelease: Boolean = false, saveState: Boolean = true) {
        releaseActiveBackend()
        exoPlayer?.let { player ->
            if (saveState) {
                currentWindowIndex = player.currentMediaItemIndex
                currentPosition = player.currentPosition
                playWhenReady = player.playWhenReady
            }
            playerListener?.let { player.removeListener(it) }
            player.release()
        }
        mediaSession?.release()
        mediaSession = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        exoPlayer = null

        // Зануляем коллбеки ТОЛЬКО если это полное уничтожение плеера (выход из приложения),
        // чтобы не сломать "горячую перезагрузку" при смене настроек.
        if (isFinalRelease) {
            playerListener = null
            onMetadataAvailable = null
            onPlayerCreated = null
            onVideoFormatChanged = null
            onAudioOutputFormatChanged = null
        }
    }

    fun getTrackMetadata(): Map<Int, UnifiedMetadataReader.TrackInfo> = currentTrackInfo

    fun togglePlayPause() { if (isPlaying()) pause() else play() }

    fun seekForward() { seekTo((getPositionMs() + 15000).coerceAtMost(getDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE)) }

    fun seekBack() { seekTo((getPositionMs() - 15000).coerceAtLeast(0)) }

    fun bindSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        boundSurfaceHolder = surfaceHolder
        activeBackend?.attachSurfaceHolder(surfaceHolder)
        vlcBackend?.attachSurfaceHolder(surfaceHolder)
        media3Backend?.attachSurfaceHolder(surfaceHolder)
    }

    fun getActiveBackendId(): String = when (activeBackend) {
        vlcBackend -> "VLC"
        media3Backend -> "MEDIA3"
        else -> "NONE"
    }


    fun getCurrentUri(): String? = when (activeBackend) {
        vlcBackend -> currentPlaylistItems.getOrNull(currentWindowIndex)?.uri?.toString()
        else -> exoPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
    }
    fun getCurrentWindowIndex(): Int = when (activeBackend) {
        vlcBackend -> currentWindowIndex
        else -> exoPlayer?.currentMediaItemIndex ?: currentWindowIndex
    }
    fun getPlaylistSize(): Int = currentPlaylistItems.size
    fun getCurrentTitle(): String? = when (activeBackend) {
        vlcBackend -> currentPlaylistItems.getOrNull(currentWindowIndex)?.title
        else -> exoPlayer?.currentMediaItem?.mediaMetadata?.title?.toString()
    }
    fun getPlaybackStateCompat(): Int = when {
        activeBackend === vlcBackend && isPlaying() -> Player.STATE_READY
        activeBackend === vlcBackend && getPositionMs() > 0L && !isPlaying() -> Player.STATE_READY
        activeBackend === vlcBackend -> Player.STATE_BUFFERING
        else -> exoPlayer?.playbackState ?: Player.STATE_IDLE
    }

    fun getPositionMs(): Long = activeBackend?.getPositionMs() ?: (exoPlayer?.currentPosition ?: 0L)
    fun getDurationMs(): Long = activeBackend?.getDurationMs() ?: (exoPlayer?.duration ?: 0L)
    fun isPlaying(): Boolean = activeBackend?.isPlaying() ?: (exoPlayer?.isPlaying == true)
    fun getBufferedPositionMs(): Long = activeBackend?.getBufferedPositionMs() ?: (exoPlayer?.bufferedPosition ?: 0L)
    fun getBufferedPercentage(): Int = activeBackend?.getBufferedPercentage() ?: (exoPlayer?.bufferedPercentage ?: 0)
    fun play() = activeBackend?.play()
    fun pause() = activeBackend?.pause()
    fun seekTo(positionMs: Long) = activeBackend?.seekTo(positionMs)

    fun releaseActiveBackend() {
        activeBackend?.stop()
        activeBackend?.release()
        if (activeBackend === vlcBackend) vlcBackend = null
        if (activeBackend === media3Backend) media3Backend = null
        activeBackend = null
    }

    fun maybeFallbackToVlcOnError(error: Throwable): Boolean {
        val playbackEngine = settingsRepo.getPlaybackEngine()
        val engineAllowsFallback = playbackEngine == SettingsRepository.PLAYBACK_ENGINE_AUTO ||
                playbackEngine == SettingsRepository.PLAYBACK_ENGINE_VLC_FALLBACK
        if (!engineAllowsFallback || !settingsRepo.isFallbackOnVideoDecoderErrorEnabled()) {
            Log.i(vlcTag, "Fallback not allowed engine=$playbackEngine enabled=${settingsRepo.isFallbackOnVideoDecoderErrorEnabled()}")
            return false
        }
        val isDecoder = isVideoDecoderError(error)
        Log.i(vlcTag, "Error classification videoDecoder=$isDecoder")
        if (!isDecoder) return false
        Log.w(vlcTag, "Media3 decoder error detected, starting fallback: ${error.message}")
        val player = exoPlayer ?: return false
        val index = player.currentMediaItemIndex
        if (index !in currentPlaylistItems.indices) return false
        val item = currentPlaylistItems[index]
        val pos = player.currentPosition
        val indexSaved = player.currentMediaItemIndex
        Log.i(vlcTag, "Fallback start index=$indexSaved positionMs=$pos")
        playerListener?.let { player.removeListener(it) }
        player.release()
        exoPlayer = null
        media3Backend = null
        mediaSession?.release()
        mediaSession = null
        val backend = VlcBackend(appContext, settingsRepo)
        backend.attachSurfaceHolder(boundSurfaceHolder)
        attachVlcListener(backend)
        Log.i(vlcTag, "VLC prepare uri=${item.uri}")
        backend.prepare(item.uri, item.headers, pos)
        Log.i(vlcTag, "Fallback seek positionMs=$pos")
        vlcBackend = backend
        activeBackend = backend
        Log.i(backendTag, "Active backend=VLC")
        return true
    }


    private fun attachVlcListener(backend: VlcBackend) {
        backend.setListener(object : PlaybackBackend.Listener {
            override fun onBuffering() { onBackendBufferingChanged?.invoke(true) }
            override fun onPlaying() {
                Log.i(vlcTag, "VLC playing")
                onBackendBufferingChanged?.invoke(false)
                onBackendPlayingChanged?.invoke(true)
            }
            override fun onPaused() { onBackendPlayingChanged?.invoke(false) }
            override fun onEnded() { onBackendEnded?.invoke() }
            override fun onError(error: Throwable) {
                Log.e(vlcTag, "VLC error", error)
                onBackendError?.invoke(error)
            }
        })
    }

    private fun isVideoDecoderError(error: Throwable): Boolean {
        val msg = error.stackTraceToString().lowercase()
        return msg.contains("error_code_decoding_failed") ||
                msg.contains("mediacodecvideorenderer") ||
                msg.contains("video/hevc") ||
                msg.contains("c2.google.hevc.decoder") ||
                msg.contains("decoder initialization")
    }

}