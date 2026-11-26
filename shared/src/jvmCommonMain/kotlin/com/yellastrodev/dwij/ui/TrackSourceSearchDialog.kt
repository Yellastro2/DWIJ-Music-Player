package com.yellastrodev.dwij.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.SourcePreviewControls
import com.yellastrodev.dwij.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Показывает только онлайн-сервисы, ещё не представленные у этой песни. */
internal fun Song.missingOnlineSources(): List<TrackSourceIndicator> = buildList {
    if (yandexInstances.isEmpty()) add(TrackSourceIndicator.YANDEX)
    if (vkInstances.isEmpty()) add(TrackSourceIndicator.VK)
}

/** Метаданные результата хранятся только до закрытия поиска или подтверждения. */
private data class SourceResult(
    val instance: TrackInstance,
    val title: String,
    val artistNames: List<String>,
    val durationMs: Long?,
)

/** Накопленные строки сервиса и номер страницы ЯМ либо смещение VK для продолжения. */
private data class SourceResultPage(val rows: List<SourceResult>, val nextCursor: Int? = null)

/** Показывает найденные треки стандартными строками с обложками и длительностью.
 * Раскрывает текст активного результата и управляет прослушиванием, перемоткой и привязкой.
 */
@Composable
internal fun TrackSourceSearchDialog(
    song: Song,
    component: DwijComponent,
    onDismiss: () -> Unit,
    onLinked: (Set<String>, Song) -> Unit,
) {
    val sources = remember(song.id) { song.missingOnlineSources() }
    if (sources.isEmpty()) return
    var source by remember(song.id) { mutableStateOf(sources.first()) }
    val results = remember(song.id) { mutableStateMapOf<TrackSourceIndicator, SourceResultPage>() }
    var searchError by remember(source) { mutableStateOf(false) }
    var retry by remember(song.id, source) { mutableIntStateOf(0) }
    var requestedCursor by remember(song.id, source) { mutableStateOf<Int?>(null) }
    var preview by remember(song.id) { mutableStateOf<SourceResult?>(null) }
    val previewControls = remember(preview) { preview?.let { SourcePreviewControls() } }
    var playbackError by remember(song.id) { mutableStateOf(false) }
    var selected by remember(song.id) { mutableStateOf<SourceResult?>(null) }
    var saving by remember(song.id) { mutableStateOf(false) }
    var linkError by remember(song.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failureMessage = stringResource(Res.string.multi_source_link_error)

    LaunchedEffect(song.id, source, retry, requestedCursor) {
        val requestedSource = source
        val current = results[requestedSource]
        val cursor = requestedCursor ?: if (current == null) 0 else return@LaunchedEffect
        try {
            val page = withContext(Dispatchers.IO) { searchOtherSource(component, song, requestedSource, cursor) }
            results[requestedSource] = page.copy(rows = current?.rows.orEmpty() + page.rows)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            searchError = true
        }
        requestedCursor = null
    }
    LaunchedEffect(song.id, previewControls) {
        val candidate = preview ?: return@LaunchedEffect
        val controls = previewControls ?: return@LaunchedEffect
        var temporary: java.io.File? = null
        try {
            val (uri, hls) = withContext(Dispatchers.IO) {
                when (val instance = candidate.instance) {
                    is TrackInstance.Yandex -> {
                        val file = component.trackCacheRepo.readyFile(instance.track.id)
                            ?: java.io.File.createTempFile("dwij-source-preview-", ".mp3").also { output ->
                                temporary = output
                                require(component.trackRepository.downloadSourcePreview(instance.track, output))
                            }
                        file.toURI().toString() to false
                    }
                    is TrackInstance.Vk -> {
                        val uri = requireNotNull(component.vkMusicRepository.playbackUri(instance))
                        uri to java.net.URI(uri).path.endsWith(".m3u8", ignoreCase = true)
                    }
                    is TrackInstance.Local -> error("Ожидался онлайн-источник")
                }
            }
            component.playerRepo.withSourcePreview { component.playSourcePreview(uri, hls, controls) }
            if (preview == candidate) preview = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (preview == candidate) {
                playbackError = true
                preview = null
            }
        } finally {
            temporary?.delete()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(Res.string.multi_source_search)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sources.size > 1) {
                    Row {
                        sources.forEach { option ->
                            TextButton(
                                enabled = option != source && !saving,
                                onClick = { source = option },
                            ) { Text(sourceName(option)) }
                        }
                    }
                } else {
                    Text(sourceName(source))
                }
                if (playbackError) Text(stringResource(Res.string.multi_source_preview_error))
                linkError?.let { Text(it) }
                val page = results[source]
                val rows = page?.rows
                when {
                    rows == null && searchError -> {
                        Text(stringResource(Res.string.multi_source_search_error))
                        TextButton(onClick = { searchError = false; retry++ }) {
                            Text(stringResource(Res.string.multi_source_search_retry))
                        }
                    }
                    rows == null -> Text(stringResource(Res.string.multi_source_search_loading))
                    rows.isEmpty() -> Text(stringResource(Res.string.multi_source_search_empty))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(rows, key = { index, row -> "$index:${row.instance.id}" }) { _, row ->
                            val instance = row.instance
                            val yandexUnavailable = instance is TrackInstance.Yandex && !instance.track.available
                            val coverState = remember(instance.id) { TrackCoverState() }
                            TrackCoverLoader(trackId = instance.id, coverState = coverState) {
                                withContext(Dispatchers.IO) {
                                    val cover = when (instance) {
                                        is TrackInstance.Yandex -> component.coverRepository.getTrackCover(instance.track)
                                        is TrackInstance.Vk -> component.coverRepository.getVkTrackCover(instance.track)
                                        is TrackInstance.Local -> null
                                    }
                                    cover?.toImageBitmapOrNull()
                                }
                            }
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    TrackListItem(
                                        item = TrackListItemUiModel(
                                            key = instance.id,
                                            trackId = instance.id,
                                            title = row.title,
                                            artist = row.artistNames.joinToString(", "),
                                            isPlaybackBlocked = yandexUnavailable,
                                        ),
                                        coverState = coverState,
                                        onClick = {
                                            playbackError = false
                                            preview = if (preview == row) null else row
                                        },
                                        modifier = Modifier.weight(1f),
                                        enabled = !saving && !yandexUnavailable,
                                        expandMetadata = row == preview,
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        row.durationMs?.takeIf { it > 0 }?.let { duration ->
                                            Text(formatSourceDuration(duration), style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(enabled = !saving, onClick = { selected = row }) {
                                            Text(stringResource(Res.string.multi_source_link))
                                        }
                                    }
                                }
                                if (row == preview && previewControls != null) {
                                    SourcePreviewSlider(previewControls, row.durationMs)
                                    Text(
                                        stringResource(Res.string.multi_source_preview_active),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        page?.nextCursor?.let { nextCursor ->
                            item(key = "load-more") {
                                Column {
                                    if (searchError) Text(stringResource(Res.string.multi_source_search_error))
                                    TextButton(
                                        enabled = !saving && requestedCursor == null,
                                        onClick = {
                                            searchError = false
                                            requestedCursor = nextCursor
                                        },
                                    ) {
                                        val label = when {
                                            requestedCursor != null -> Res.string.multi_source_search_loading
                                            searchError -> Res.string.multi_source_search_retry
                                            else -> Res.string.multi_source_search_more
                                        }
                                        Text(stringResource(label))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(Res.string.multi_source_dialog_cancel))
            }
        },
    )

    selected?.let { row ->
        AlertDialog(
            onDismissRequest = { if (!saving) selected = null },
            title = { Text(stringResource(Res.string.multi_source_link)) },
            text = {
                Text(
                    stringResource(
                        Res.string.multi_source_link_confirmation,
                        song.artistNames.joinToString(", "),
                        song.title,
                        sourceName(source),
                        row.artistNames.joinToString(", "),
                        row.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    preview = null
                    linkError = null
                    scope.launch {
                        try {
                            val (oldIds, updated) = withContext(Dispatchers.IO) {
                                component.songMatchRepository.withManualLink {
                                    linkSearchResult(component, song.id, row.instance)
                                }
                            }
                            onLinked(oldIds, updated)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            linkError = error.message ?: failureMessage
                            selected = null
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(stringResource(Res.string.multi_source_link)) }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { selected = null }) {
                    Text(stringResource(Res.string.multi_source_dialog_cancel))
                }
            },
        )
    }
}

/** Показывает позицию активного результата и отправляет перемотку при отпускании ползунка. */
@Composable
private fun SourcePreviewSlider(controls: SourcePreviewControls, expectedDurationMs: Long?) {
    val progress by controls.progress.collectAsState()
    var draggedPosition by remember(controls) { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs.takeIf { it > 0 } ?: expectedDurationMs ?: 0L
    val current = draggedPosition?.toLong() ?: progress.positionMs
    val position = current.coerceIn(0L, duration.coerceAtLeast(0L))
    Slider(
        value = position.toFloat(),
        onValueChange = { draggedPosition = it },
        onValueChangeFinished = {
            draggedPosition?.let { controls.seekTo(it.toLong()) }
            draggedPosition = null
        },
        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
        enabled = progress.canSeek && progress.durationMs > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatSourceDuration(position), style = MaterialTheme.typography.labelSmall)
        if (duration > 0) Text(formatSourceDuration(duration), style = MaterialTheme.typography.labelSmall)
    }
}

/** Форматирует известную длительность трека как минуты и две цифры секунд. */
private fun formatSourceDuration(durationMs: Long): String {
    val seconds = durationMs / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Имя сервиса в переключателе и подтверждении. */
@Composable
private fun sourceName(source: TrackSourceIndicator): String =
    if (source == TrackSourceIndicator.VK) "VK" else stringResource(Res.string.multi_source_yandex_name)

/** Запрашивает выдачу сервиса по названию и артисту, сохраняя все строки в исходном порядке. */
private suspend fun searchOtherSource(
    component: DwijComponent,
    song: Song,
    source: TrackSourceIndicator,
    cursor: Int,
): SourceResultPage {
    val query = "${song.title} ${song.artistNames.joinToString(", ")}".trim()
    return when (source) {
        TrackSourceIndicator.YANDEX -> when (val result = component.searchRepository.searchTrackPage(query, cursor)) {
            is DataResult.Success -> {
                val section = result.value ?: return SourceResultPage(emptyList())
                val rows = section.results.map { track ->
                    SourceResult(
                        TrackInstance.Yandex("yandex:${track.id}", track.toEntity()), track.title,
                        track.artists.map { it.name }, track.durationMs?.toLong(),
                    )
                }
                val next = (cursor + 1).takeIf {
                    rows.isNotEmpty() && section.perPage > 0 && it.toLong() * section.perPage < section.total
                }
                SourceResultPage(rows, next)
            }
            is DataResult.Failure -> error("Не удалось выполнить поиск в Яндекс Музыке")
        }
        TrackSourceIndicator.VK -> when (val result = component.vkMusicRepository.searchPage(query, cursor)) {
            is DataResult.Success -> {
                val page = result.value
                val rows = page.items.map { track ->
                    SourceResult(
                        TrackInstance.Vk("vk:${track.fullId}", track), track.title,
                        track.artistNames, track.duration * 1000,
                    )
                }
                val next = (cursor + page.items.size).takeIf { rows.isNotEmpty() && it < page.count }
                SourceResultPage(rows, next)
            }
            is DataResult.Failure -> error("Не удалось выполнить поиск в VK")
        }
        TrackSourceIndicator.LOCAL -> SourceResultPage(emptyList())
    }
}

/** Сохраняет выбранный источник после подтверждения и возвращает обновлённую песню. */
private suspend fun linkSearchResult(
    component: DwijComponent,
    songId: String,
    instance: TrackInstance,
): Pair<Set<String>, Song> {
    val repository = component.songRepository
    val candidate = when (instance) {
        is TrackInstance.Yandex -> {
            val tracks = listOf(instance.track)
            repository.songsForYandexTracks(tracks).singleOrNull() ?: run {
                component.trackRepository.putTracks(tracks)
                repository.songsForYandexTracks(tracks).single()
            }
        }
        is TrackInstance.Vk -> {
            val tracks = listOf(instance.track)
            repository.songsForVkTracks(tracks).singleOrNull() ?: run {
                repository.registerVkTracks(tracks)
                repository.songsForVkTracks(tracks).single()
            }
        }
        is TrackInstance.Local -> error("Ожидался онлайн-источник")
    }
    val candidateInstance = when (instance) {
        is TrackInstance.Yandex -> candidate.yandexInstances.single { it.track.id == instance.track.id }
        is TrackInstance.Vk -> candidate.vkInstances.single { it.track.fullId == instance.track.fullId }
        is TrackInstance.Local -> error("Ожидался онлайн-источник")
    }
    val updatedId = repository.linkOnlineInstance(songId, candidateInstance.id)
    return setOf(songId, candidate.id) to repository.songsByIds(listOf(updatedId)).single()
}
