package com.yellastrodev.dwij.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.repo.CatalogAlbumPage
import com.yellastrodev.dwij.data.repo.CatalogArtistPage
import com.yellastrodev.dwij.data.repo.CatalogRepository
import com.yellastrodev.dwij.data.repo.CoverData
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.artistTrackKey
import com.yellastrodev.yamusicsdk.entities.CoverSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.reflect.KClass

/** Поддержанные разновидности экранов каталога. */
enum class CatalogObjectKind {
    Artist,
    Album,
}

/** Снимок экрана каталога с отдельным состоянием подгрузки треков артиста. */
@Immutable
data class CatalogObjectUiState(
    val kind: CatalogObjectKind = CatalogObjectKind.Artist,
    val externalId: Int? = null,
    val title: String = "",
    val artistNames: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val coverUri: String? = null,
    val releaseDate: String? = null,
    val year: Int? = null,
    val likesCount: Long? = null,
    val trackCount: Int? = null,
    val lastMonthListeners: Long? = null,
    val metadataSource: String? = null,
    val tracks: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: DataError? = null,
    val nextArtistTrackPage: Int? = null,
    val isLoadingArtistTracks: Boolean = false,
    val artistTracksError: DataError? = null,
)

/** Загружает объект каталога и страницы треков артиста, запускает воспроизведение списка. */
class CatalogObjectModel(
    private val catalogRepository: CatalogRepository,
    private val coverRepository: CoverRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogObjectUiState())
    val state: StateFlow<CatalogObjectUiState> = _state.asStateFlow()
    private var objectLoad: Job? = null
    private var artistTracksLoad: Job? = null

    /** Загружает карточку, отменяя предыдущую загрузку карточки и подгрузку треков. */
    fun load(kind: CatalogObjectKind, externalId: Int, force: Boolean = false) {
        val current = _state.value
        val sameObject = current.externalId == externalId && current.kind == kind
        if (!force && sameObject) return

        objectLoad?.cancel()
        artistTracksLoad?.cancel()
        _state.value = (if (sameObject) current else CatalogObjectUiState()).copy(
            kind = kind,
            externalId = externalId,
            isLoading = true,
            error = null,
            isLoadingArtistTracks = false,
        )
        objectLoad = viewModelScope.launch {
            val result: DataResult<Any> = try {
                when (kind) {
                    CatalogObjectKind.Artist -> catalogRepository.artist(externalId)
                    CatalogObjectKind.Album -> catalogRepository.album(externalId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DataResult.Failure(DataError.Unknown(error))
            }
            ensureActive()
            _state.value = when (result) {
                is DataResult.Success -> when (val page = result.value) {
                    is CatalogArtistPage -> page.toUiState(externalId)
                    is CatalogAlbumPage -> page.toUiState(externalId)
                    else -> error("Неизвестный тип страницы каталога")
                }
                is DataResult.Failure -> _state.value.copy(
                    isLoading = false,
                    error = result.error,
                )
            }
            if (result is DataResult.Success && kind == CatalogObjectKind.Artist) {
                loadMoreArtistTracks()
            }
        }
    }

    /** Добавляет следующую страницу после популярных треков либо повторяет ту же страницу. */
    fun loadMoreArtistTracks() {
        val current = _state.value
        val artistId = current.externalId ?: return
        val page = current.nextArtistTrackPage ?: return
        if (current.isLoading || current.isLoadingArtistTracks) return

        _state.value = current.copy(isLoadingArtistTracks = true, artistTracksError = null)
        artistTracksLoad = viewModelScope.launch {
            val result = try {
                catalogRepository.artistTracks(artistId, page)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DataResult.Failure(DataError.Unknown(error))
            }
            ensureActive()
            val latest = _state.value
            _state.value = when (result) {
                is DataResult.Success -> latest.copy(
                    tracks = (latest.tracks + result.value.tracks).distinctBy(Song::artistTrackKey),
                    nextArtistTrackPage = result.value.nextPage,
                    isLoadingArtistTracks = false,
                )
                is DataResult.Failure -> latest.copy(
                    isLoadingArtistTracks = false,
                    artistTracksError = result.error,
                )
            }
        }
    }

    fun refresh() {
        val externalId = _state.value.externalId ?: return
        load(_state.value.kind, externalId, force = true)
    }

    /** Запускает выбранную строку и передаёт плееру курсор каталога на момент запуска. */
    fun play(index: Int): Boolean {
        val current = _state.value
        val songs = current.tracks
        val externalId = current.externalId ?: return false
        if (index !in songs.indices) return false
        val tracklist = CatalogTracklist(current.kind, externalId, current.title)
        val catalog = catalogRepository
        viewModelScope.launch {
            if (current.kind == CatalogObjectKind.Artist) {
                playerRepository.playArtistQueue(songs, index, tracklist, current.nextArtistTrackPage) { page ->
                    catalog.artistTracks(externalId, page)
                }
            } else {
                playerRepository.playQueue(songs, index, tracklist)
            }
        }
        return true
    }

    suspend fun objectCover(): CoverData? {
        val current = _state.value
        val uri = current.coverUri ?: return null
        val externalId = current.externalId ?: return null
        return withContext(Dispatchers.IO) {
            coverRepository.getRemoteCover(
                entityType = current.kind.name.lowercase(),
                entityId = externalId.toString(),
                url = uri,
                size = CoverSize.`400x400`,
            )
        }
    }

    suspend fun trackCover(songId: String): CoverData? {
        val track = _state.value.tracks
            .firstOrNull { it.id == songId }
            ?.yandexInstances
            ?.firstOrNull()
            ?.track
            ?: return null
        return withContext(Dispatchers.IO) {
            coverRepository.getTrackCover(track, CoverSize.`100x100`)
        }
    }

    /** Показывает популярные треки сразу и открывает подгрузку каталога с нулевой страницы. */
    private fun CatalogArtistPage.toUiState(externalId: Int) = CatalogObjectUiState(
        kind = CatalogObjectKind.Artist,
        externalId = externalId,
        title = name,
        genres = genres,
        coverUri = coverUri,
        likesCount = likesCount,
        trackCount = trackCount,
        lastMonthListeners = lastMonthListeners,
        metadataSource = metadataSource,
        tracks = tracks.distinctBy(Song::artistTrackKey),
        nextArtistTrackPage = 0,
    )

    private fun CatalogAlbumPage.toUiState(externalId: Int) = CatalogObjectUiState(
        kind = CatalogObjectKind.Album,
        externalId = externalId,
        title = title,
        artistNames = artistNames,
        description = description,
        coverUri = coverUri,
        releaseDate = releaseDate,
        year = year,
        likesCount = likesCount,
        metadataSource = metadataSource,
        tracks = tracks,
    )

    class Factory(
        private val catalogRepository: CatalogRepository,
        private val coverRepository: CoverRepository,
        private val playerRepository: PlayerRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            if (modelClass == CatalogObjectModel::class) {
                return CatalogObjectModel(
                    catalogRepository,
                    coverRepository,
                    playerRepository,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

/** Контекст ЯМ-каталога с продолжением очереди и перемешиванием каталога артиста. */
private data class CatalogTracklist(
    val kind: CatalogObjectKind,
    val externalId: Int,
    val title: String,
) : dTracklist {
    override fun getdId(): String = "${kind.name.lowercase()}:$externalId"
    override fun getDTitle(): String = title
    override fun getType(): String = kind.name.lowercase()
    override fun getWaveId(): String = ""
    /** Для артиста существует поддерживаемый rotor seed; внутренний список альбома его не задаёт. */
    override fun yandexWaveSeed(): String? =
        if (kind == CatalogObjectKind.Artist && externalId > 0) "artist:$externalId" else null
    /** Добавляет треки артиста в случайные позиции среди ожидающих. */
    override fun interleaveAppendedTracks(): Boolean = kind == CatalogObjectKind.Artist
    /** Каталог содержит сущности ЯМ. */
    override fun originOf(songId: String): com.yellastrodev.dwij.data.entities.MusicSource =
        com.yellastrodev.dwij.data.entities.MusicSource.YANDEX
}
