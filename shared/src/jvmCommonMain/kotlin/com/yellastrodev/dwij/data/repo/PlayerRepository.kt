package com.yellastrodev.dwij.data.repo


import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.playback.RepeatMode
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yamusicsdk.YamLogger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yellastrodev.dwij.playback.OfflinePlaybackPolicy

/** Управляет очередью, подгрузкой каталога артиста, офлайном и реакциями. */
class PlayerRepository(
    private val engine: PlayerEngine,
    private val settings: PlaybackSettings,
    private val scope: CoroutineScope,
    private val isTrackCached: (trackId: String) -> Boolean,
    private val prefetchTrack: suspend (trackId: String) -> Unit,
    private val continueWave: suspend (dTracklist) -> Unit,
    private val logger: YamLogger,
    private val resolveVkUri: (com.yellastrodev.dwij.data.entities.TrackInstance.Vk) -> String? = { null },
    private val resolveOfflineTrack: (Song) -> PlaybackTrack? = { null },
    private val networkFailure: (PlaybackTrack, Boolean) -> Boolean = { track, network -> track.source != MusicSource.LOCAL && network },
    private val probeOnline: suspend (List<Song>) -> Boolean = { false },
) : PlaybackQueue {

    private val offlinePolicy = OfflinePlaybackPolicy()
    val isOffline: StateFlow<Boolean> = offlinePolicy.offline
    @Volatile private var originalSongs: List<Song> = emptyList()
    @Volatile private var offlinePositions: List<Int> = emptyList()
    private val rejectedOfflinePositions = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var userQueueGeneration = 0L
    private val queueCommandMutex = Mutex()
    private val sourcePreviewMutex = Mutex()
    @Volatile private var offlineTransition: Job? = null
    @Volatile private var probeJob: Job? = null
    private var lastProbeMs = 0L

    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private val mutableTracklist = MutableStateFlow<dTracklist?>(null)
    val dtracklist: StateFlow<dTracklist?> = mutableTracklist.asStateFlow()

    var currentTrackList: List<String> = emptyList()
        private set

    @Volatile private var currentSongQueue: List<Song> = emptyList()
    @Volatile private var currentPlaybackQueue: List<PlaybackTrack> = emptyList()
    @Volatile private var queueRevision = 0L
    private val queueRestoreLock = Any()

    private var prefetchJob: Job? = null
    private var lastPrefetchKey: String? = null
    @Volatile private var artistQueue: ArtistQueue? = null
    @Volatile private var artistLoadJob: Job? = null
    private val playbackCommandVersion = AtomicLong()

    private val mutableCurrentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = mutableCurrentTrack.asStateFlow()

    private val mutableCurrentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = mutableCurrentSong.asStateFlow()

    private val mutableCurrentPlaybackTrack =
        MutableStateFlow<PlaybackTrack?>(null)

    override val currentPlaybackTrack: StateFlow<PlaybackTrack?> =
        mutableCurrentPlaybackTrack.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PlayerEvent>()
    override val events: SharedFlow<PlayerEvent> = mutableEvents.asSharedFlow()

    private val mutableIsShuffleBlock = MutableStateFlow(false)
    override val isShuffleBlock: StateFlow<Boolean> =
        mutableIsShuffleBlock.asStateFlow()

    var relativeIndex: Int = 0
        private set

    init {
        engine.setFailureHandler(::onTerminalFailure)
        engine.state
            .onEach { playerState ->
                offlinePolicy.progress(playerState)
                val previous = mutableState.value
                if (playerState.currentIndex != previous.currentIndex) {
                    updateCurrentTrack(playerState.currentIndex)
                    scheduleOnlineProbe()
                }

                mutableState.value = if (artistQueue?.nextPage != null) {
                    playerState.copy(isRepeatAll = settings.repeatMode == RepeatMode.ALL)
                } else playerState
                schedulePrefetch(playerState)
                if (playerState.currentIndex != previous.currentIndex ||
                    playerState.isShuffle != previous.isShuffle ||
                    (playerState.wantsToPlay && !previous.wantsToPlay)
                ) {
                    scheduleArtistTracks()
                }
            }
            .launchIn(scope)

        engine.events
            .onEach(::handleEngineEvent)
            .launchIn(scope)
    }

    /** Продолжает каталог артиста, а после его конца разрешает повтор или волну. */
    private suspend fun handleEngineEvent(event: PlayerEvent) {
        if (event is PlayerEvent.TrackListEnd) {
            val generation = userQueueGeneration
            artistQueue?.let { continuation ->
                if (!continuation.ready) return
                val endedIndex = engine.state.value.currentIndex
                val commandVersion = playbackCommandVersion.get()
                fillArtistQueue(continuation, generation, minimumAhead = 1, retry = true)
                val handled = queueCommandMutex.withLock {
                    if (generation != userQueueGeneration || artistQueue !== continuation ||
                        commandVersion != playbackCommandVersion.get() ||
                        engine.state.value.currentIndex != endedIndex ||
                        engine.state.value.isPlaying || engine.state.value.pendingTrackChange != null
                    ) return@withLock true

                    val next = engine.getUpcomingIndices(1).firstOrNull()
                    when {
                        next != null -> engine.playTrack(next)
                        continuation.nextPage != null -> return@withLock false
                        settings.repeatMode == RepeatMode.ALL && !isOffline.value -> engine.skipNext()
                        else -> return@withLock false
                    }
                    true
                }
                if (handled) return
                if (continuation.nextPage != null) {
                    if (!isOffline.value) emitArtistLoadError()
                    else mutableEvents.emit(event)
                    return
                }
            }
            val tracklist = dtracklist.value

            if (
                generation == userQueueGeneration && !isOffline.value && tracklist != null &&
                !tracklist.yandexWaveSeed().isNullOrBlank()
            ) {
                continueWave(tracklist)
                return
            }
        }

        mutableEvents.emit(event)
    }

    /**
     * Запускает очередь логических песен, выбирая для каждой
     * воспроизводимый экземпляр.
     * VK callback разрешает лишь локальный адрес relay; сетевые запросы остаются ленивыми.
     * В офлайне сохраняет исходный состав отдельно и выбирает только готовые локальные экземпляры.
     *
     * [startIndex] переводится из индекса исходного списка
     * в индекс уже отфильтрованной воспроизводимой очереди.
     */
    override suspend fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        tracklist: dTracklist,
    ) {
        tryPlayQueue(songs, startIndex, tracklist)
    }

    /** Возвращает результат подготовки; offlineOnly разрешает только готовые файлы без сетевого fallback. */
    suspend fun tryPlayQueue(songs: List<Song>, startIndex: Int, tracklist: dTracklist): Boolean =
        startQueue(songs, startIndex, tracklist, continuation = null)

    /** Запускает каталог артиста и передаёт подгрузку очереди в scope плеера. */
    suspend fun playArtistQueue(
        songs: List<Song>,
        startIndex: Int,
        tracklist: dTracklist,
        nextPage: Int?,
        loadPage: suspend (Int) -> DataResult<CatalogArtistTrackPage>,
    ) {
        startQueue(
            songs, startIndex, tracklist,
            ArtistQueue(nextPage, songs.mapTo(mutableSetOf(), Song::artistTrackKey), loadPage),
        )
    }

    /** Подготавливает воспроизводимые экземпляры и связывает продолжение с новой очередью. */
    private suspend fun startQueue(
        songs: List<Song>,
        startIndex: Int,
        tracklist: dTracklist,
        continuation: ArtistQueue?,
    ): Boolean {
        if (songs.isEmpty() || startIndex !in songs.indices) {
            logger.warning(
                TAG,
                "[playQueue] Некорректная исходная очередь: " +
                        "size=${songs.size}, startIndex=$startIndex",
            )
            return false
        }

        val generation = synchronized(this) {
            artistLoadJob?.cancel()
            artistQueue = null
            ++userQueueGeneration
        }
        offlineTransition?.cancel()
        probeJob?.cancel()
        originalSongs = songs
        offlinePositions = emptyList()
        rejectedOfflinePositions.clear()
        offlinePolicy.resetStreak()

        val playableSongs = withContext(Dispatchers.IO) {
            songs.mapIndexedNotNull { index, song ->
                resolveSong(song, tracklist.offlineOnly())?.let { playbackTrack ->
                    IndexedValue(
                        index = index,
                        value = song to playbackTrack,
                    )
                }
            }
        }
        if (generation != userQueueGeneration) return false

        if (playableSongs.isEmpty()) {
            if (isOffline.value || tracklist.offlineOnly()) {
                artistQueue = continuation
                mutableTracklist.value = tracklist
                clearUnavailableQueue(generation)
                mutableEvents.emit(PlayerEvent.ShowError("В этой очереди нет треков, доступных без сети"))
                scheduleOnlineProbe()
            }
            logger.warning(
                TAG,
                "[playQueue] В очереди нет воспроизводимых экземпляров",
            )
            return false
        }

        offlinePositions = if (isOffline.value) playableSongs.map { it.index } else emptyList()

        val resolvedStartIndex = playableSongs
            .indexOfFirst { indexedSong ->
                indexedSong.index == startIndex
            }
            .takeIf { index -> index >= 0 }
            ?: playableSongs
                .indexOfFirst { indexedSong ->
                    indexedSong.index > startIndex
                }
                .takeIf { index -> index >= 0 }
            ?: 0

        val skippedCount = songs.size - playableSongs.size

        if (skippedCount > 0) {
            logger.debug(
                TAG,
                "[playQueue] Пропущено песен без воспроизводимых " +
                        "экземпляров=$skippedCount, исходный index=$startIndex, " +
                        "итоговый index=$resolvedStartIndex",
            )
        }

        playPreparedQueue(
            songs = playableSongs.map { indexedSong ->
                indexedSong.value.first
            },
            tracks = playableSongs.map { indexedSong ->
                indexedSong.value.second
            },
            startIndex = resolvedStartIndex,
            tracklist = tracklist,
            expectedGeneration = generation,
            artistContinuation = continuation,
        )
        scheduleOnlineProbe()
        if (generation == userQueueGeneration) {
            continuation?.ready = true
            scheduleArtistTracks()
        }
        return generation == userQueueGeneration
    }

    /** Публикует очередь, включая восстановленную без контекста; autoPlay=false сохраняет паузу. */
    private suspend fun playPreparedQueue(
        songs: List<Song>,
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
        autoPlay: Boolean = true,
        expectedGeneration: Long = userQueueGeneration,
        artistContinuation: ArtistQueue? = artistQueue,
    ): Unit = queueCommandMutex.withLock {
        if (expectedGeneration != userQueueGeneration) return
        if (tracks.isEmpty() || startIndex !in tracks.indices) {
            logger.warning(
                TAG,
                "[playQueue] Некорректная подготовленная очередь: " +
                        "size=${tracks.size}, startIndex=$startIndex",
            )
            return
        }

        logger.debug(TAG, "[playQueue] Подготовка очереди")

        engine.prepare()
        if (expectedGeneration != userQueueGeneration) return
        artistQueue = artistContinuation

        if (tracklist != null && dtracklist.value?.getdId() == tracklist.getdId()) {
            logger.debug(TAG, "[playQueue] Треклист не изменился")

            val newSongIds = songs.map(Song::id)

            val sameInstances =
                currentPlaybackQueue.map { it.instanceId to it.playbackUri } ==
                        tracks.map { it.instanceId to it.playbackUri }

            if (
                currentTrackList == newSongIds &&
                sameInstances
            ) {
                if (artistContinuation != null) applyArtistRepeatMode()
                if (state.value.currentIndex == startIndex) {
                    if (!engine.state.value.wantsToPlay) engine.togglePlayPause()
                    logger.debug(
                        TAG,
                        "[playQueue] Выбранная позиция очереди уже играет",
                    )
                    return
                }

                mutableCurrentTrack.value = songs[startIndex].id
                mutableCurrentSong.value = songs[startIndex]
                mutableCurrentPlaybackTrack.value =
                    currentPlaybackQueue.getOrNull(startIndex)

                relativeIndex = startIndex

                engine.playTrack(startIndex)

                logger.debug(
                    TAG,
                    "[playQueue] Переключаем текущую очередь на " +
                            "index=$startIndex, songId=${songs[startIndex].id}",
                )
                return
            }

            logger.debug(
                TAG,
                "[playQueue] Состав текущего треклиста изменился",
            )
        }

        blockShuffle(
            isWave = tracklist?.getType() == dYaWave.Companion.YA_WAVE || tracklist?.preserveQueueOrder() == true,
        )

        synchronized(queueRestoreLock) {
            currentTrackList = songs.map(Song::id)
            currentSongQueue = songs
            currentPlaybackQueue = tracks
            queueRevision += 1L
            cancelPrefetch()

            mutableCurrentTrack.value = songs[startIndex].id
            mutableCurrentSong.value = songs[startIndex]
            mutableCurrentPlaybackTrack.value = tracks[startIndex]
            mutableTracklist.value = tracklist

            relativeIndex = startIndex
        }

        logger.debug(
            TAG,
            "[playQueue] Формируем очередь: size=${tracks.size}, " +
                    "startIndex=$startIndex, songId=${songs[startIndex].id}, " +
                    "instanceId=${tracks[startIndex].instanceId}",
        )

        engine.setQueue(
            tracks = tracks,
            startIndex = startIndex,
            tracklist = tracklist,
            autoPlay = autoPlay,
        )

        logger.debug(
            TAG,
            "[playQueue] Очередь передана в engine",
        )
    }

    private suspend fun blockShuffle(
        isWave: Boolean,
    ) {
        mutableIsShuffleBlock.value = isWave

        if (isWave) {
            engine.setShuffleEnabled(false)
            engine.setRepeatMode(RepeatMode.OFF)
        } else {
            applySavedModes()
        }
    }

    /**
     * Догружает в текущую очередь песни,
     * для которых удалось выбрать воспроизводимый экземпляр.
     * Адреса VK создаются тем же callback, что и при запуске полной очереди.
     */
    override suspend fun addTracks(
        songs: List<Song>,
    ): Unit = queueCommandMutex.withLock {
        if (isOffline.value || dtracklist.value?.offlineOnly() == true) {
            // Волна и другая автоматическая догрузка не расширяют офлайн-очередь сетевыми треками.
            return
        }
        val resolved = withContext(Dispatchers.IO) {
            songs.mapNotNull { song ->
                song.toPlaybackTrack(isTrackCached, resolveVkUri)?.let { playbackTrack ->
                    song to playbackTrack
                }
            }
        }
        if (isOffline.value || dtracklist.value?.offlineOnly() == true) return

        val skippedCount = songs.size - resolved.size

        logger.debug(
            TAG,
            "[addTracks] Получено=${songs.size}, " +
                    "добавляем=${resolved.size}, пропущено=$skippedCount",
        )

        if (resolved.isEmpty()) {
            return
        }

        appendResolvedTracks(resolved)
    }

    /** Обновляет таблицу индексов и движок под общим mutex команд очереди. */
    private suspend fun appendResolvedTracks(resolved: List<Pair<Song, PlaybackTrack>>) {
        val resolvedSongs = resolved.map { pair ->
            pair.first
        }

        val playbackTracks = resolved.map { pair ->
            pair.second
        }
        originalSongs = originalSongs + resolvedSongs

        synchronized(queueRestoreLock) {
            currentTrackList = currentTrackList + resolvedSongs.map(Song::id)

            currentSongQueue = currentSongQueue + resolvedSongs

            currentPlaybackQueue = currentPlaybackQueue + playbackTracks

            queueRevision += 1L
        }

        engine.appendTracks(
            tracks = playbackTracks,
            tracklist = dtracklist.value,
        )

        schedulePrefetch(state.value)
    }

    /** Запускает одну фоновую подгрузку и расширяет запас при включении shuffle. */
    @Synchronized
    private fun scheduleArtistTracks() {
        val continuation = artistQueue ?: return
        if (!continuation.ready || continuation.nextPage == null || continuation.failed || isOffline.value ||
            artistLoadJob?.isActive == true
        ) return
        val generation = userQueueGeneration
        artistLoadJob = scope.launch {
            fillArtistQueue(continuation, generation)
        }
    }

    /** Догружает страницы до нужного запаса в фактическом порядке плеера. */
    private suspend fun fillArtistQueue(
        continuation: ArtistQueue,
        generation: Long,
        minimumAhead: Int? = null,
        retry: Boolean = false,
    ) {
        var retryFailedPage = retry
        while (generation == userQueueGeneration && artistQueue === continuation &&
            continuation.nextPage != null && !isOffline.value
        ) {
            val ahead = minimumAhead ?: if (engine.state.value.isShuffle) {
                ARTIST_SHUFFLE_AHEAD_COUNT
            } else ARTIST_AHEAD_COUNT
            if (engine.getUpcomingIndices(ahead).size >= ahead) return
            if (!loadArtistPage(continuation, generation, retryFailedPage)) return
            retryFailedPage = false
            yield()
        }
    }

    /** Сериализует запросы курсора и публикует страницу только для прежней очереди. */
    private suspend fun loadArtistPage(
        continuation: ArtistQueue,
        generation: Long,
        retry: Boolean,
    ): Boolean = continuation.pageMutex.withLock {
        val page = continuation.nextPage ?: return false
        if (generation != userQueueGeneration || artistQueue !== continuation ||
            isOffline.value || (continuation.failed && !retry)
        ) return false

        val result = try {
            continuation.loadPage(page)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Unknown(error))
        }
        when (result) {
            is DataResult.Failure -> {
                continuation.failed = true
                logger.warning(TAG, "[loadArtistPage] Не удалось загрузить страницу $page каталога артиста")
                false
            }
            is DataResult.Success -> {
                val newSongs = result.value.tracks.distinctBy(Song::artistTrackKey)
                    .filter { it.artistTrackKey() !in continuation.seen }
                val resolved = withContext(Dispatchers.IO) {
                    newSongs.mapNotNull { song ->
                        song.toPlaybackTrack(isTrackCached, resolveVkUri)?.let { song to it }
                    }
                }
                queueCommandMutex.withLock {
                    if (generation != userQueueGeneration || artistQueue !== continuation || isOffline.value) {
                        return false
                    }
                    if (resolved.isNotEmpty()) appendResolvedTracks(resolved)
                    newSongs.mapTo(continuation.seen, Song::artistTrackKey)
                    continuation.failed = false
                    continuation.nextPage = result.value.nextPage
                    if (continuation.nextPage == null) applyArtistRepeatMode()
                }
                true
            }
        }
    }

    /** Сообщает об ошибке, сохраняя курсор для повторного нажатия «Далее». */
    private suspend fun emitArtistLoadError() {
        mutableEvents.emit(PlayerEvent.ShowError("Не удалось загрузить треки артиста. Нажмите «Далее», чтобы повторить"))
    }

    /** На время прослушивания освобождает звук и восстанавливает исходное воспроизведение. */
    suspend fun withSourcePreview(block: suspend () -> Unit) = sourcePreviewMutex.withLock {
        val before = engine.state.value
        val generation = userQueueGeneration
        val command = playbackCommandVersion.incrementAndGet()
        var pausedForPreview = false
        try {
            if (before.wantsToPlay) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    engine.togglePlayPause()
                    pausedForPreview = withTimeoutOrNull(2_000L) {
                        engine.state.first { !it.wantsToPlay }
                        true
                    } == true
                }
                check(pausedForPreview) { "Основной плеер не приостановился" }
            }
            if (generation != userQueueGeneration || command != playbackCommandVersion.get()) return@withLock
            coroutineScope {
                val preview = launch { block() }
                val playback = launch {
                    engine.state.first { state ->
                        state.wantsToPlay || state.currentIndex != before.currentIndex ||
                            state.pendingTrackChange != before.pendingTrackChange ||
                            generation != userQueueGeneration
                    }
                    preview.cancel()
                }
                try {
                    preview.join()
                } finally {
                    playback.cancel()
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                val current = engine.state.value
                if (pausedForPreview && !current.wantsToPlay &&
                    current.pendingTrackChange == before.pendingTrackChange &&
                    current.currentIndex == before.currentIndex && generation == userQueueGeneration &&
                    command == playbackCommandVersion.get()
                ) engine.togglePlayPause()
            }
        }
    }

    /** Переключает паузу, отменяя ожидающий автоматический переход. */
    fun pause() {
        playbackCommandVersion.incrementAndGet()
        scope.launch {
            engine.togglePlayPause()
        }
    }

    /** На границе каталога ждёт следующую страницу перед ручным переходом. */
    suspend fun skipNext() {
        val commandVersion = playbackCommandVersion.incrementAndGet()
        val continuation = artistQueue
        if (continuation?.nextPage != null) {
            val generation = userQueueGeneration
            val index = engine.state.value.currentIndex
            fillArtistQueue(continuation, generation, minimumAhead = 1, retry = true)
            val needsRetry = queueCommandMutex.withLock {
                if (generation != userQueueGeneration || artistQueue !== continuation ||
                    commandVersion != playbackCommandVersion.get() || index != engine.state.value.currentIndex
                ) return
                if (continuation.nextPage != null && engine.getUpcomingIndices(1).isEmpty()) {
                    true
                } else {
                    engine.skipNext()
                    false
                }
            }
            if (needsRetry && !isOffline.value) emitArtistLoadError()
            return
        }
        engine.skipNext()
    }

    /** Возвращается по истории, отменяя ожидающий переход вперёд. */
    suspend fun skipPrev() {
        playbackCommandVersion.incrementAndGet()
        engine.skipPrevious()
    }

    /** Перематывает текущий трек, отменяя ожидающий переход после его конца. */
    fun seekTo(
        positionMs: Long,
    ) {
        playbackCommandVersion.incrementAndGet()
        scope.launch {
            engine.seekTo(positionMs)
        }
    }

    fun shuffle() {
        if (isShuffleBlock.value) {
            return
        }

        val newValue = !state.value.isShuffle

        settings.shuffleEnabled = newValue

        scope.launch {
            engine.setShuffleEnabled(newValue)
        }
    }

    /** Сохраняет выбранный повтор, откладывая зацикливание до конца каталога артиста. */
    fun rotate() {
        if (isShuffleBlock.value) {
            return
        }

        val newMode = if (state.value.isRepeatAll) {
            RepeatMode.OFF
        } else {
            RepeatMode.ALL
        }

        settings.repeatMode = newMode

        scope.launch {
            queueCommandMutex.withLock {
                if (!isShuffleBlock.value) applyArtistRepeatMode()
            }
        }
    }

    /** Восстанавливает настройки для новой очереди с учётом открытого курсора каталога. */
    private suspend fun applySavedModes() {
        engine.setShuffleEnabled(
            settings.shuffleEnabled,
        )

        applyArtistRepeatMode()
    }

    /** Отключает фактический повтор неполной очереди, сохраняя выбор в интерфейсе. */
    private suspend fun applyArtistRepeatMode() {
        val pending = artistQueue?.nextPage != null
        val requested = settings.repeatMode
        engine.setRepeatMode(if (pending) RepeatMode.OFF else requested)
        if (pending) mutableState.value = mutableState.value.copy(isRepeatAll = requested == RepeatMode.ALL)
    }

    fun List<Song>.stableHash(): Int {
        return fold(1) { accumulator, song ->
            31 * accumulator + song.id.hashCode()
        }
    }

    private fun updateCurrentTrack(
        index: Int,
    ) {
        val song = currentSongQueue.getOrNull(index)
        val playbackTrack = currentPlaybackQueue.getOrNull(index)

        mutableCurrentTrack.value = song?.id
        mutableCurrentSong.value = song
        mutableCurrentPlaybackTrack.value = playbackTrack
    }

    /**
     * Принимает фактическую очередь после смерти процесса, не вызывая команды движка.
     * Пустой контекст не выдумывает seed волны; новая пользовательская очередь имеет приоритет.
     */
    fun restoreQueueIfEmpty(
        songs: List<Song>,
        tracks: List<PlaybackTrack>,
        playerState: PlayerState,
    ): Boolean = synchronized(queueRestoreLock) {
        if (currentSongQueue.isNotEmpty() || songs.isEmpty() ||
            songs.size != tracks.size || playerState.currentIndex !in songs.indices
        ) return@synchronized false

        currentTrackList = songs.map(Song::id)
        currentSongQueue = songs
        currentPlaybackQueue = tracks
        originalSongs = songs
        queueRevision += 1L
        mutableTracklist.value = null
        mutableIsShuffleBlock.value = false
        relativeIndex = playerState.currentIndex
        mutableState.value = playerState
        updateCurrentTrack(playerState.currentIndex)
        logger.debug(TAG, "[restoreQueueIfEmpty] Очередь принята из плеера: size=${songs.size}, index=${playerState.currentIndex}")
        true
    }

    /**
     * Поддерживает в LRU-кэше два следующих ЯМ-трека плейлиста или волны.
     * Задание запускается только после подготовки текущего трека, чтобы фоновая
     * загрузка не заняла файловый mutex раньше воспроизведения.
     */
    private fun schedulePrefetch(
        playerState: PlayerState,
    ) {
        if (isOffline.value) {
            cancelPrefetch()
            return
        }
        val tracklist = dtracklist.value
        val isEligibleTracklist = when (tracklist?.getType()) {
            dYaPlaylist.YA_PLAYLIST,
            dYaWave.YA_WAVE -> true
            else -> false
        }

        if (
            !isEligibleTracklist ||
            playerState.pendingTrackChange != null
        ) {
            cancelPrefetch()
            return
        }

        val currentTrack =
            currentPlaybackQueue.getOrNull(playerState.currentIndex)
                ?: run {
                    cancelPrefetch()
                    return
                }

        val requestKey =
            "$queueRevision|${playerState.isShuffle}|${currentTrack.instanceId}"

        if (requestKey == lastPrefetchKey) {
            return
        }

        lastPrefetchKey = requestKey
        prefetchJob?.cancel()

        val queueSnapshot = currentPlaybackQueue

        prefetchJob = scope.launch(Dispatchers.IO) {
            val upcomingIndices = engine.getUpcomingIndices(
                PREFETCH_AHEAD_COUNT,
            )

            val trackIds = upcomingIndices
                .mapNotNull(queueSnapshot::getOrNull)
                .filter { track ->
                    track.source == MusicSource.YANDEX
                }
                .map(PlaybackTrack::id)
                .distinct()
                .filterNot(isTrackCached)

            trackIds.forEach { trackId ->
                try {
                    prefetchTrack(trackId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[schedulePrefetch] Не удалось предзагрузить трек $trackId",
                        error,
                    )
                }
            }
        }
    }

    private fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        lastPrefetchKey = null
    }

    /** Разрешение офлайн никогда не вызывает сетевой resolver и не использует неполный кеш. */
    private fun resolveSong(song: Song, offlineOnly: Boolean): PlaybackTrack? = if (isOffline.value || offlineOnly) resolveOfflineTrack(song)
        else song.toPlaybackTrack(isTrackCached, resolveVkUri)

    /** Принимает решение синхронно; команды выполняются отдельной задачей после выхода из recovery движка. */
    private fun onTerminalFailure(index: Int, platformNetworkFailure: Boolean): Boolean {
        val track = currentPlaybackQueue.getOrNull(index) ?: return false
        val wasOffline = isOffline.value
        val network = networkFailure(track, platformNetworkFailure)
        if (!offlinePolicy.failed(queueRevision, index, network)) return false
        cancelPrefetch()
        val generation = userQueueGeneration
        val revision = queueRevision
        offlineTransition?.cancel()
        offlineTransition = scope.launch {
            yield()
            if (generation != userQueueGeneration || revision != queueRevision) return@launch
            val songs = if (offlinePositions.isEmpty()) currentSongQueue else originalSongs
            val failedOriginal = offlinePositions.getOrNull(index) ?: index
            if (wasOffline) rejectedOfflinePositions += failedOriginal
            val tracklist = dtracklist.value
            val upcoming = engine.getUpcomingIndices(currentSongQueue.size)
                .map { offlinePositions.getOrNull(it) ?: it }
            val local = withContext(Dispatchers.IO) {
                songs.mapIndexedNotNull { position, song ->
                    if (position in rejectedOfflinePositions) null
                    else resolveOfflineTrack(song)?.let { Triple(position, song, it) }
                }
            }
            if (generation != userQueueGeneration || revision != queueRevision) return@launch
            if (offlinePositions.isEmpty()) originalSongs = songs
            logger.warning(TAG, "[onTerminalFailure] Офлайн: доступно=${local.size}, отказавший индекс=$failedOriginal")
            if (local.isEmpty()) {
                clearUnavailableQueue(generation)
                mutableEvents.emit(PlayerEvent.ShowError("Офлайн: в очереди нет доступных локально треков"))
            } else {
                val preferred = (listOf(failedOriginal) + upcoming).firstNotNullOfOrNull { candidate ->
                    local.indexOfFirst { it.first == candidate }.takeIf { it >= 0 }
                } ?: 0
                offlinePositions = local.map { it.first }
                val wantsToPlay = engine.state.value.wantsToPlay
                playPreparedQueue(local.map { it.second }, local.map { it.third }, preferred, tracklist, wantsToPlay, generation)
            }
            scheduleOnlineProbe()
        }
        return true
    }

    /** Пустой офлайн-список не оставляет сетевую очередь доступной кнопке Play или гарнитуре. */
    private suspend fun clearUnavailableQueue(expectedGeneration: Long): Unit = queueCommandMutex.withLock {
        if (expectedGeneration != userQueueGeneration) return
        engine.stopPlayback(clearQueue = true)
        currentSongQueue = emptyList()
        currentPlaybackQueue = emptyList()
        currentTrackList = emptyList()
        mutableCurrentSong.value = null
        mutableCurrentTrack.value = null
        mutableCurrentPlaybackTrack.value = null
        offlinePositions = emptyList()
        queueRevision++
    }

    /** Проверяет восстановление автоматического офлайна; явно локальная коллекция не запускает probe. */
    @Synchronized private fun scheduleOnlineProbe() {
        if (!isOffline.value || dtracklist.value?.offlineOnly() == true || probeJob?.isActive == true) return
        val generation = userQueueGeneration
        probeJob = scope.launch {
            // Переход в офлайн сначала завершает замену очереди и текущие отмены транспорта.
            yield()
            offlineTransition?.join()
            while (isOffline.value && generation == userQueueGeneration && dtracklist.value?.offlineOnly() != true) {
                val probeIndex = engine.state.value.currentIndex
                val elapsed = System.nanoTime() / 1_000_000 - lastProbeMs
                if (elapsed < 15_000) delay(15_000 - elapsed)
                if (generation != userQueueGeneration || dtracklist.value?.offlineOnly() == true) return@launch
                lastProbeMs = System.nanoTime() / 1_000_000
                val online = try {
                    withTimeoutOrNull(8_000) { probeOnline(originalSongs) } == true
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    logger.debug(TAG, "[probeOnline] Онлайн ещё недоступен: ${error.javaClass.simpleName}")
                    false
                }
                if (online && generation == userQueueGeneration) {
                    restoreOnlineQueue(generation)
                    if (!isOffline.value) return@launch
                }
                delay(15_000)
                if (engine.state.value.isPlaying && engine.state.value.currentIndex == probeIndex) return@launch
            }
        }
    }

    /** Восстанавливает автоматический офлайн вокруг текущего файла; явная локальная очередь не меняется. */
    private suspend fun restoreOnlineQueue(generation: Long) {
        if (generation != userQueueGeneration || dtracklist.value?.offlineOnly() == true) return
        val revision = queueRevision
        val resolved = withContext(Dispatchers.IO) {
            originalSongs.mapIndexedNotNull { index, song ->
                song.toPlaybackTrack(isTrackCached, resolveVkUri)?.let { Triple(index, song, it) }
            }
        }
        if (generation != userQueueGeneration || revision != queueRevision || resolved.isEmpty()) return
        val index = engine.state.value.currentIndex
        val originalIndex = offlinePositions.getOrNull(index) ?: index
        val restoredIndex = resolved.indexOfFirst { it.first == originalIndex }
        if (offlinePositions.isNotEmpty() && restoredIndex >= 0) {
            queueCommandMutex.withLock {
                if (generation != userQueueGeneration || revision != queueRevision) return
                val tracks = resolved.map { it.third }.toMutableList()
                currentPlaybackQueue.getOrNull(index)?.let { tracks[restoredIndex] = it }
                if (!engine.replaceQueueKeepingCurrent(tracks, index, restoredIndex, dtracklist.value) {
                        generation == userQueueGeneration && revision == queueRevision
                    }) return
                currentSongQueue = resolved.map { it.second }
                currentPlaybackQueue = tracks
                currentTrackList = currentSongQueue.map(Song::id)
                queueRevision++
                updateCurrentTrack(restoredIndex)
            }
        } else if (currentSongQueue.isEmpty()) {
            val tracklist = dtracklist.value
            offlinePolicy.onlineConfirmed()
            playPreparedQueue(resolved.map { it.second }, resolved.map { it.third },
                restoredIndex.coerceAtLeast(0), tracklist, autoPlay = false, expectedGeneration = generation)
        }
        if (generation != userQueueGeneration) return
        offlinePositions = emptyList()
        rejectedOfflinePositions.clear()
        offlinePolicy.onlineConfirmed()
        logger.info(TAG, "[restoreOnlineQueue] Сетевая проверка успешна, обычная очередь восстановлена")
        schedulePrefetch(engine.state.value)
        artistQueue?.ready = true
        scheduleArtistTracks()
    }

    /**
     * Заменяет объединённые Song в уже играющей очереди,
     * не перезапуская платформенный плеер.
     */
    fun applyMergedSong(
        sourceSongIds: Set<String>,
        mergedSong: Song,
    ) {
        if (sourceSongIds.isEmpty()) {
            return
        }
        originalSongs = originalSongs.map { if (it.id in sourceSongIds) mergedSong else it }

        currentSongQueue = currentSongQueue.map { song ->
            if (song.id in sourceSongIds) {
                mergedSong
            } else {
                song
            }
        }

        currentTrackList = currentTrackList.map { songId ->
            if (songId in sourceSongIds) {
                mergedSong.id
            } else {
                songId
            }
        }

        val currentSongId = currentSong.value?.id

        if (currentSongId != null && currentSongId in sourceSongIds) {
            mutableCurrentSong.value = mergedSong
            mutableCurrentTrack.value = mergedSong.id
        }
    }

    /** Обновляет данные существующей Song в очереди без смены позиции и перезапуска плеера. */
    fun applyUpdatedSong(updatedSong: Song) {
        originalSongs = originalSongs.map { if (it.id == updatedSong.id) updatedSong else it }
        currentSongQueue = currentSongQueue.map { song ->
            if (song.id == updatedSong.id) updatedSong else song
        }
        if (currentSong.value?.id == updatedSong.id) {
            mutableCurrentSong.value = updatedSong
        }
    }

    /** Обновляет подтверждённый лайк во всех экземплярах очереди без замены их метадаты и временных ID. */
    fun applyYandexReaction(trackId: String, liked: Boolean) {
        originalSongs = originalSongs.map { it.withYandexReaction(trackId, liked) }
        currentSongQueue = currentSongQueue.map { it.withYandexReaction(trackId, liked) }
        mutableCurrentSong.value = mutableCurrentSong.value?.withYandexReaction(trackId, liked)
    }

    /** Курсор, дедупликация и сериализация запросов одной запущенной очереди артиста. */
    private class ArtistQueue(
        @Volatile var nextPage: Int?,
        val seen: MutableSet<String>,
        val loadPage: suspend (Int) -> DataResult<CatalogArtistTrackPage>,
    ) {
        val pageMutex = Mutex()
        @Volatile var failed = false
        @Volatile var ready = false
    }

    private companion object {
        const val TAG = "PlayerRepository"
        const val PREFETCH_AHEAD_COUNT = 2
        const val ARTIST_AHEAD_COUNT = 4
        const val ARTIST_SHUFFLE_AHEAD_COUNT = 100
    }
}

/** Применяет реакцию только к песне, содержащей исходный ЯМ-трек. */
internal fun Song.withYandexReaction(trackId: String, liked: Boolean): Song =
    if (yandexInstances.any { it.track.id == trackId } && isLiked != liked) copy(isLiked = liked) else this
