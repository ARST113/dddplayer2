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
import androidx.media3.exoplayer.ExoPlaybackException
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
import top.rootu.dddplayer.engine.EngineError
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
    private var nativeBackend: NativePlaybackBackend? = null
    private var currentPlaybackSpeed: Float = 1f
    private var forceVlcForCurrentPlaylistSession: Boolean = false
    var onMetadataAvailable: (() -> Unit)? = null
    var onPlayerCreated: ((ExoPlayer) -> Unit)? = null
    var onVideoFormatChanged: ((Format) -> Unit)? = null
    var onAudioOutputFormatChanged: ((String) -> Unit)? = null
    var onBackendPlayingChanged: ((Boolean) -> Unit)? = null
    var onBackendBufferingChanged: ((Boolean) -> Unit)? = null
    var onBackendEnded: (() -> Unit)? = null
    var onBackendError: ((Throwable) -> Unit)? = null
    var onBackendPositionChanged: ((Long, Long) -> Unit)? = null
    var onBackendVideoSizeChanged: ((Int, Int, Float) -> Unit)? = null

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

    fun initializePlayer(
        forceMedia3SurfaceFallback: Boolean = false,
        preferAv1Dav1d: Boolean = false
    ) {
        // Playback is intentionally single-engine. Codec-specific decoder choices
        // (including AV1 Surface output) live inside NativePlaybackBackend and must
        // never replace the whole demux/timeline/audio engine with Media3 or VLC.
        Log.i(backendTag, "DDD Unified selected; Media3/VLC initialization skipped")
        if (activeBackend !== nativeBackend) releaseActiveBackend()
        exoPlayer?.release()
        exoPlayer = null
        media3Backend = null
        vlcBackend?.release()
        vlcBackend = null
        mediaSession?.release()
        mediaSession = null
        return

        @Suppress("UNREACHABLE_CODE")
        if (activeBackend === nativeBackend || nativeBackend != null) {
            nativeBackend?.release()
            if (activeBackend === nativeBackend) activeBackend = null
            nativeBackend = null
        }
        if (activeBackend === vlcBackend || vlcBackend != null) {
            Log.i(backendTag, "Releasing VLC before Media3 initialization")
            vlcBackend?.release()
            if (activeBackend === vlcBackend) activeBackend = null
            vlcBackend = null
        }
        if (exoPlayer != null) {
            releasePlayer(isFinalRelease = false, saveState = true)
        }

        // === Кастомный MediaCodecSelector для Dolby Vision Fallback ===
        val mediaCodecSelector = if (settingsRepo.isMapDvToHevcEnabled() || preferAv1Dav1d) {
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
                    val decoders = MediaCodecUtil.getDecoderInfos(
                        finalMimeType,
                        requiresSecureDecoder,
                        requiresTunnelingDecoder
                    )
                    if (preferAv1Dav1d && mimeType == MimeTypes.VIDEO_AV1) {
                        // c2.android.av1-dav1d cannot expose CPU ByteBuffers, but it
                        // can decode efficiently when Media3 gives it the final
                        // Surface directly. Prefer it only for this format-specific
                        // escape hatch; the native GLES path remains authoritative
                        // for HEVC, Dolby Vision and all supported frame formats.
                        decoders.sortedBy { info ->
                            when (info.name) {
                                AV1_DAV1D_DECODER -> 0
                                AV1_ANDROID_DECODER -> 1
                                else -> 2
                            }
                        }.also { sorted ->
                            Log.i(backendTag, "AV1 Surface decoders=${sorted.joinToString { it.name }}")
                        }
                    } else {
                        decoders
                    }
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
            setExtensionRendererMode(
                if (preferAv1Dav1d) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                else settingsRepo.getDecoderPriority()
            )
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
        media3Backend?.setPlaybackSpeed(currentPlaybackSpeed)
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
        forceVlcForCurrentPlaylistSession = false
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

        run {
            releaseActiveBackend()
            exoPlayer?.release()
            exoPlayer = null
            media3Backend = null
            mediaSession?.release()
            mediaSession = null
            val selected = items.getOrNull(startIndex) ?: return
            val backend = NativePlaybackBackend(createParsingDataSourceFactory())
            nativeBackend = backend
            backend.attachSurfaceHolder(boundSurfaceHolder)
            attachNativeListener(backend)
            backend.setPlaybackSpeed(currentPlaybackSpeed)
            activeBackend = backend
            backend.prepare(selected.uri, selected.headers, startPosMs)
            Log.i(backendTag, "Active engine=DDD_UNIFIED uri=${selected.uri}")
            return
        }

        if (settingsRepo.getPlaybackEngine() == SettingsRepository.PLAYBACK_ENGINE_VLC_ONLY) {
            if (activeBackend === nativeBackend) releaseActiveBackend()
            if (activeBackend === media3Backend) {
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                media3Backend = null
                mediaSession?.release()
                mediaSession = null
            }
            // TODO(MVP): controller-driven next/previous for VLC_ONLY should reopen item via PlayerManager/loadPlaylist.
            // TODO(MVP): persist contentKey -> preferred backend (AUTO) after successful fallback.
            val selected = items.getOrNull(startIndex) ?: return
            val backend = vlcBackend ?: VlcBackend(appContext, settingsRepo).also { vlcBackend = it }
            backend.attachSurfaceHolder(boundSurfaceHolder)
            attachVlcListener(backend)
            backend.setPlaybackSpeed(currentPlaybackSpeed)
            Log.i(vlcTag, "VLC prepare uri=${selected.uri}")
            activeBackend = backend
            backend.prepare(selected.uri, selected.headers, startPosMs, selected.subtitles)
            Log.i(backendTag, "Active backend=VLC")
            return
        }
        if (activeBackend === nativeBackend) releaseActiveBackend()
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

    fun seekForward() {
        val cur = getPositionMs(); val target = (cur + 15000).coerceAtMost(getDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE)
        seekTo(target)
        Log.i(backendTag, "seekForward backend=${getActiveBackendId()} current=$cur target=$target actual=${getPositionMs()}")
    }

    fun seekBack() {
        val cur = getPositionMs(); val target = (cur - 15000).coerceAtLeast(0)
        seekTo(target)
        Log.i(backendTag, "seekBack backend=${getActiveBackendId()} current=$cur target=$target actual=${getPositionMs()}")
    }

    fun bindSurfaceHolder(surfaceHolder: SurfaceHolder?) {
        boundSurfaceHolder = surfaceHolder
        nativeBackend?.attachSurfaceHolder(surfaceHolder)
        vlcBackend?.attachSurfaceHolder(surfaceHolder)
        media3Backend?.attachSurfaceHolder(surfaceHolder)
        if (activeBackend !== nativeBackend && activeBackend !== vlcBackend &&
            activeBackend !== media3Backend) {
            activeBackend?.attachSurfaceHolder(surfaceHolder)
        }
    }

    fun getActiveBackendId(): String = when {
        activeBackend != null && activeBackend === nativeBackend -> "NATIVE"
        activeBackend != null && activeBackend === vlcBackend -> "VLC"
        activeBackend != null && activeBackend === media3Backend -> "MEDIA3"
        else -> "NONE"
    }


    fun getCurrentUri(): String? = when {
        activeBackend != null && (activeBackend === vlcBackend || activeBackend === nativeBackend) ->
            currentPlaylistItems.getOrNull(currentWindowIndex)?.uri?.toString()
        else -> exoPlayer?.currentMediaItem?.localConfiguration?.uri?.toString()
    }
    fun getCurrentWindowIndex(): Int = when {
        activeBackend != null && (activeBackend === vlcBackend || activeBackend === nativeBackend) ->
            currentWindowIndex
        else -> exoPlayer?.currentMediaItemIndex ?: currentWindowIndex
    }
    fun getPlaylistSize(): Int = currentPlaylistItems.size
    fun getCurrentTitle(): String? = when {
        activeBackend != null && (activeBackend === vlcBackend || activeBackend === nativeBackend) ->
            currentPlaylistItems.getOrNull(currentWindowIndex)?.title
        else -> exoPlayer?.currentMediaItem?.mediaMetadata?.title?.toString()
    }
    fun getPlaybackStateCompat(): Int = when {
        activeBackend != null && activeBackend !== media3Backend && isPlaying() -> Player.STATE_READY
        activeBackend != null && activeBackend !== media3Backend && getPositionMs() > 0L && !isPlaying() -> Player.STATE_READY
        activeBackend != null && activeBackend !== media3Backend -> Player.STATE_BUFFERING
        else -> exoPlayer?.playbackState ?: Player.STATE_IDLE
    }

    fun getPositionMs(): Long = activeBackend?.getPositionMs() ?: (exoPlayer?.currentPosition ?: 0L)
    fun getDurationMs(): Long = activeBackend?.getDurationMs() ?: (exoPlayer?.duration ?: 0L)
    fun isPlaying(): Boolean = activeBackend?.isPlaying() ?: (exoPlayer?.isPlaying == true)
    fun getBufferedPositionMs(): Long = activeBackend?.getBufferedPositionMs() ?: (exoPlayer?.bufferedPosition ?: 0L)
    fun getBufferedPercentage(): Int = activeBackend?.getBufferedPercentage() ?: (exoPlayer?.bufferedPercentage ?: 0)
    fun play() = activeBackend?.play()
    fun pause() = activeBackend?.pause()
    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        activeBackend?.setPlaybackSpeed(speed)
    }
    fun seekTo(positionMs: Long) {
        Log.i(backendTag, "seekTo backend=${getActiveBackendId()} target=$positionMs current=${getPositionMs()}")
        activeBackend?.seekTo(positionMs)
    }


    fun hasNext(): Boolean = getCurrentWindowIndex() < currentPlaylistItems.lastIndex
    fun hasPrevious(): Boolean = getCurrentWindowIndex() > 0

    fun playIndex(index: Int, startPositionMs: Long = 0L): Boolean {
        if (index !in currentPlaylistItems.indices) return false
        currentWindowIndex = index
        val item = currentPlaylistItems[index]
        Log.i(backendTag, "playlist index=$index uri=${item.uri} title=${item.title}")
        val backend = nativeBackend ?: NativePlaybackBackend(createParsingDataSourceFactory()).also {
            nativeBackend = it
        }
        backend.attachSurfaceHolder(boundSurfaceHolder)
        attachNativeListener(backend)
        backend.setPlaybackSpeed(currentPlaybackSpeed)
        activeBackend = backend
        backend.prepare(item.uri, item.headers, startPositionMs)
        Log.i(backendTag, "playIndex DDD_UNIFIED index=$index uri=${item.uri}")
        return true
    }

    fun next(): Boolean {
        val target = getCurrentWindowIndex() + 1
        Log.i(vlcTag, "next current=${getCurrentWindowIndex()} target=$target")
        return playIndex(target)
    }

    fun previous(): Boolean {
        val target = getCurrentWindowIndex() - 1
        Log.i(vlcTag, "previous current=${getCurrentWindowIndex()} target=$target")
        return playIndex(target)
    }

    fun getVlcAudioTracks(): List<BackendAudioTrack> = when {
        activeBackend != null && activeBackend === nativeBackend -> nativeBackend?.getAudioTracks()
        else -> vlcBackend?.getAudioTracks()
    } ?: emptyList()
    fun getVlcSelectedAudioTrackId(): Int? = when {
        activeBackend != null && activeBackend === nativeBackend ->
            nativeBackend?.getSelectedAudioTrackId()
        else -> vlcBackend?.getSelectedAudioTrack()
    }
    fun selectVlcAudioTrackById(id: Int): Boolean = when {
        activeBackend != null && activeBackend === nativeBackend ->
            nativeBackend?.selectAudioTrack(id) == true
        else -> vlcBackend?.selectAudioTrack(id) == true
    }
    fun getVlcSubtitleTracks(): List<BackendSubtitleTrack> = vlcBackend?.getSubtitleTracks() ?: emptyList()
    fun getVlcSelectedSubtitleTrackId(): Int? = vlcBackend?.getSelectedSubtitleTrack()
    fun selectVlcSubtitleTrackById(id: Int): Boolean = vlcBackend?.selectSubtitleTrack(id) == true
    fun getCurrentAudioTrackLabel(): String? {
        return when {
        activeBackend != null && (activeBackend === vlcBackend || activeBackend === nativeBackend) -> {
            val tracks = getVlcAudioTracks()
            if (tracks.isEmpty()) return null
            val selected = getVlcSelectedAudioTrackId()
            tracks.firstOrNull { it.id == selected }?.label ?: tracks.firstOrNull()?.label
        }
        else -> exoPlayer?.currentTracks
            ?.groups
            ?.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
            ?.let { group ->
                val idx = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return@let null
                group.getTrackFormat(idx).label
            }
        }
    }

    fun releaseActiveBackend() {
        activeBackend?.stop()
        activeBackend?.release()
        if (activeBackend === vlcBackend) vlcBackend = null
        if (activeBackend === media3Backend) media3Backend = null
        if (activeBackend === nativeBackend) nativeBackend = null
        activeBackend = null
    }

    fun maybeFallbackToVlcOnError(error: Throwable): Boolean {
        Log.i(backendTag, "VLC fallback disabled; DDD_UNIFIED keeps the session error=${error.message}")
        return false
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
            override fun onPositionChanged(positionMs: Long, durationMs: Long) {
                onBackendPositionChanged?.invoke(positionMs, durationMs)
            }
            override fun onVideoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float) {
                onBackendVideoSizeChanged?.invoke(width, height, pixelWidthHeightRatio)
            }
        })
    }

    private fun attachNativeListener(backend: NativePlaybackBackend) {
        backend.setListener(object : PlaybackBackend.Listener {
            override fun onBuffering() { onBackendBufferingChanged?.invoke(true) }
            override fun onPlaying() {
                Log.i(backendTag, "DDD Native playing")
                onBackendBufferingChanged?.invoke(false)
                onBackendPlayingChanged?.invoke(true)
            }
            override fun onPaused() { onBackendPlayingChanged?.invoke(false) }
            override fun onEnded() { onBackendEnded?.invoke() }
            override fun onError(error: Throwable) {
                Log.e(backendTag, "DDD Native error", error)
                onBackendError?.invoke(error)
            }
            override fun onPositionChanged(positionMs: Long, durationMs: Long) {
                onBackendPositionChanged?.invoke(positionMs, durationMs)
            }
            override fun onVideoSizeChanged(width: Int, height: Int, pixelWidthHeightRatio: Float) {
                onBackendVideoSizeChanged?.invoke(width, height, pixelWidthHeightRatio)
            }
        })
    }

    /**
     * Pixel's AV1 software components expose their fast path only as graphic
     * buffers. The native engine intentionally requests CPU frames for its own
     * GLES conversion, so a large AV1 file needs a direct decoder -> Surface
     * route instead. This is deliberately narrow: it cannot steal DV/HEVC from
     * the native tone-map pipeline.
     */
    private fun maybeFallbackToAv1Surface(error: Throwable): Boolean {
        if (activeBackend !== nativeBackend ||
            !error.message.orEmpty().contains("libavcodec:av1", ignoreCase = true)) {
            return false
        }
        val index = currentWindowIndex
        if (index !in currentMediaItems.indices) return false
        val resumeAt = activeBackend?.getPositionMs()?.coerceAtLeast(0L) ?: 0L
        Log.w(backendTag, "AV1 CPU-frame decode unsupported; switching to direct Media3 Surface at ${resumeAt}ms")
        currentPosition = if (resumeAt > 0L) resumeAt else C.TIME_UNSET
        playWhenReady = true
        releaseActiveBackend()
        initializePlayer(forceMedia3SurfaceFallback = true, preferAv1Dav1d = true)
        return activeBackend === media3Backend
    }

    private fun isVideoDecoderError(error: Throwable): Boolean {
        if ((error as? EngineError)?.code == EngineError.Code.VIDEO_DECODER) return true

        (error as? ExoPlaybackException)?.let { exoError ->
            val rendererName = exoError.rendererName?.lowercase().orEmpty()
            val sampleMimeType = exoError.rendererFormat?.sampleMimeType?.lowercase().orEmpty()
            val containerMimeType = exoError.rendererFormat?.containerMimeType?.lowercase().orEmpty()
            val errorCodeName = exoError.errorCodeName.lowercase()
            val causeText = exoError.cause?.stackTraceToString()?.lowercase().orEmpty()

            val isVideoRenderer = exoError.type == ExoPlaybackException.TYPE_RENDERER &&
                    (rendererName.contains("videorenderer") ||
                            sampleMimeType.startsWith("video/") ||
                            containerMimeType.startsWith("video/"))
            val isDecoderFailure = errorCodeName.contains("decoder") ||
                    errorCodeName.contains("decoding") ||
                    causeText.contains("decoder")

            if (isVideoRenderer && isDecoderFailure) {
                return true
            }
        }

        val msg = error.stackTraceToString().lowercase()
        return (msg.contains("error_code_decoding_failed") && msg.contains("video")) ||
                msg.contains("mediacodecvideorenderer") ||
                msg.contains("libdav1dvideorenderer") ||
                msg.contains("ffmpegvideorenderer") ||
                msg.contains("video/av01") ||
                msg.contains("dav1ddecoderexception") ||
                msg.contains("video/hevc") ||
                msg.contains("c2.google.hevc.decoder") ||
                (msg.contains("decoder initialization") && msg.contains("video"))
    }

    private companion object {
        const val AV1_DAV1D_DECODER = "c2.android.av1-dav1d.decoder"
        const val AV1_ANDROID_DECODER = "c2.android.av1.decoder"
    }

}
