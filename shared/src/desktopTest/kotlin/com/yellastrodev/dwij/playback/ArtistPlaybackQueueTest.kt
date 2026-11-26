package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.repo.CatalogArtistTrackPage
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверяет продолжение очереди независимо от экрана и от скорости ответа каталога. */
class ArtistPlaybackQueueTest {
    /** Конец временной очереди ждёт страницу, пропускает дубли и включает сохранённый повтор. */
    @Test
    fun queueEndWaitsThroughDuplicatePageAndRestoresRepeat() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = TestEngine()
            val tracklist = ArtistTracklist()
            val waveStarted = CompletableDeferred<Unit>()
            val repo = PlayerRepository(engine, Settings(RepeatMode.ALL), scope, { false }, {},
                { waveStarted.complete(Unit) }, NoOpYamLogger)
            val song1 = YaTrack("1", "One", true).toDailyPlaylistSong()
            val song2 = YaTrack("2", "Two", true).toDailyPlaylistSong()
            val releasePage = CompletableDeferred<Unit>()

            repo.playArtistQueue(listOf(song1), 0, tracklist, nextPage = 0) { page ->
                when (page) {
                    0 -> {
                        releasePage.await()
                        DataResult.Success(CatalogArtistTrackPage(listOf(song1), nextPage = 1))
                    }
                    1 -> DataResult.Success(CatalogArtistTrackPage(listOf(song2), nextPage = null))
                    else -> error("Лишняя страница $page")
                }
            }
            assertTrue(repo.state.value.isRepeatAll)
            assertEquals(RepeatMode.OFF, engine.repeat)
            assertEquals(listOf(song1.id), repo.currentTrackList)

            engine.events.emit(PlayerEvent.TrackListEnd("Временная очередь закончилась"))
            releasePage.complete(Unit)
            assertEquals(1, withTimeout(3_000) { engine.selected.await() })
            assertEquals(listOf(song1.id, song2.id), repo.currentTrackList)
            assertEquals(RepeatMode.ALL, engine.repeat)
            assertFalse(waveStarted.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    /** После отказа тот же курсор можно повторить кнопкой «Далее». */
    @Test
    fun nextRetriesSamePageAfterFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = TestEngine()
            val repo = PlayerRepository(engine, Settings(), scope, { false }, {}, {}, NoOpYamLogger)
            val song1 = YaTrack("1", "One", true).toDailyPlaylistSong()
            val song2 = YaTrack("2", "Two", true).toDailyPlaylistSong()
            var attempts = 0
            repo.playArtistQueue(listOf(song1), 0, ArtistTracklist(), nextPage = 2) { page ->
                assertEquals(2, page)
                if (++attempts == 1) DataResult.Failure(DataError.InvalidData("Тестовая ошибка"))
                else DataResult.Success(CatalogArtistTrackPage(listOf(song2), nextPage = null))
            }
            withTimeout(3_000) { repo.skipNext() }
            assertEquals(1, withTimeout(3_000) { engine.selected.await() })
            assertEquals(2, attempts)
        } finally {
            scope.cancel()
        }
    }

    /** Запоздалая страница артиста не меняет очередь, открытую после него. */
    @Test
    fun replacingQueueDiscardsInFlightArtistPage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releasePage = CompletableDeferred<Unit>()
        try {
            val engine = TestEngine()
            val repo = PlayerRepository(engine, Settings(), scope, { false }, {}, {}, NoOpYamLogger)
            val song1 = YaTrack("1", "One", true).toDailyPlaylistSong()
            val song2 = YaTrack("2", "Two", true).toDailyPlaylistSong()
            val song3 = YaTrack("3", "Three", true).toDailyPlaylistSong()
            val entered = CompletableDeferred<Unit>()
            repo.playArtistQueue(listOf(song1), 0, ArtistTracklist(), nextPage = 0) {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    releasePage.await()
                }
                DataResult.Success(CatalogArtistTrackPage(listOf(song2), nextPage = null))
            }
            withTimeout(3_000) { entered.await() }
            repo.playQueue(listOf(song3), 0, LocalTracklist("other", "Другая очередь"))
            releasePage.complete(Unit)
            assertEquals(listOf(song3.id), repo.currentTrackList)
        } finally {
            releasePage.complete(Unit)
            scope.cancel()
        }
    }

    /** Контекст тестового артиста с разрешённым переходом в волну. */
    private class ArtistTracklist : dTracklist {
        override fun getdId() = "artist:7"
        override fun getDTitle() = "Артист"
        override fun getType() = "artist"
        override fun getWaveId() = ""
        override fun yandexWaveSeed() = "artist:7"
    }

    /** Хранит выбранный пользователем режим повтора. */
    private class Settings(override var repeatMode: RepeatMode = RepeatMode.OFF) : PlaybackSettings {
        override var shuffleEnabled = false
    }

    /** Управляет индексами линейной очереди без аудиопотока. */
    private class TestEngine : PlayerEngine {
        override val state = MutableStateFlow(PlayerState())
        override val events = MutableSharedFlow<PlayerEvent>()
        private var queue = emptyList<PlaybackTrack>()
        var repeat = RepeatMode.OFF
        val selected = CompletableDeferred<Int>()

        override suspend fun prepare() = Unit

        /** Записывает исходный состав, не запуская автоматические переходы. */
        override suspend fun setQueue(tracks: List<PlaybackTrack>, startIndex: Int, tracklist: dTracklist?, autoPlay: Boolean) {
            queue = tracks
            state.value = state.value.copy(currentIndex = startIndex, wantsToPlay = autoPlay)
        }

        /** Меняет только хвост очереди. */
        override suspend fun appendTracks(tracks: List<PlaybackTrack>, tracklist: dTracklist?) {
            queue += tracks
        }

        /** Возвращает доступные позиции после текущей. */
        override suspend fun getUpcomingIndices(limit: Int) =
            ((state.value.currentIndex + 1)..queue.lastIndex).take(limit)

        /** Публикует индекс, выбранный общим плеером после догрузки. */
        override suspend fun playTrack(index: Int) {
            state.value = state.value.copy(currentIndex = index)
            selected.complete(index)
        }

        override suspend fun togglePlayPause() = Unit
        /** Переходит на первый доступный индекс линейной очереди. */
        override suspend fun skipNext() {
            getUpcomingIndices(1).firstOrNull()?.let { playTrack(it) }
        }

        override suspend fun skipPrevious() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun setShuffleEnabled(enabled: Boolean) = Unit
        /** Фиксирует фактический повтор, переданный движку. */
        override suspend fun setRepeatMode(mode: RepeatMode) {
            repeat = mode
        }
    }
}
