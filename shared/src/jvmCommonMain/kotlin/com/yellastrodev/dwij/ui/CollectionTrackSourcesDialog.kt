package com.yellastrodev.dwij.ui

import androidx.compose.runtime.*
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Диалог источников для коллекций, которым не нужен маршрут объекта. */
@Composable
internal fun CollectionTrackSourcesDialog(
    initialSong: Song,
    component: DwijComponent,
    onDismiss: () -> Unit,
    onChanged: (Set<String>, Song) -> Unit = { _, _ -> },
) {
    var song by remember(initialSong.id) { mutableStateOf(initialSong) }
    var searching by remember(initialSong.id) { mutableStateOf(false) }
    var saving by remember(initialSong.id) { mutableStateOf(false) }
    var error by remember(initialSong.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val priorityError = stringResource(Res.string.multi_source_priority_error, "")

    if (searching) {
        TrackSourceSearchDialog(
            song = song,
            component = component,
            onDismiss = { searching = false },
            onLinked = { ids, updated ->
                song = updated
                component.playerRepo.applyMergedSong(ids, updated)
                onChanged(ids, updated)
                searching = false
            },
        )
        return
    }

    val options = remember(song) {
        song.instances.map { instance ->
            val indicator = when (instance) {
                is TrackInstance.Yandex -> TrackSourceIndicator.YANDEX
                is TrackInstance.Vk -> TrackSourceIndicator.VK
                is TrackInstance.Local -> TrackSourceIndicator.LOCAL
            }
            val unavailable = instance is TrackInstance.Yandex && !instance.track.available
            val playable = !unavailable || component.trackCacheRepo.isReadyLocally(
                (instance as TrackInstance.Yandex).track.id,
            )
            TrackSourceOptionUiModel(
                instanceId = instance.id,
                item = TrackListItemUiModel(
                    key = instance.id,
                    trackId = instance.id,
                    title = when (instance) {
                        is TrackInstance.Yandex -> instance.track.title
                        is TrackInstance.Vk -> instance.track.title
                        is TrackInstance.Local -> song.title
                    },
                    artist = song.artistNames.joinToString(", "),
                    isYandexUnavailable = unavailable,
                ),
                sourceIndicator = indicator,
                isPlayable = playable,
            )
        }
    }
    MultiSourceDialog(
        confirmedOptions = options,
        candidateOptions = emptyList(),
        preferredInstanceId = song.preferredInstanceId,
        loadCover = { null },
        onDismiss = onDismiss,
        onPreferredInstanceChange = { id ->
            saving = true
            error = null
            scope.launch {
                try {
                    val updated = withContext(Dispatchers.IO) {
                        component.songRepository.setPreferredInstance(song.id, id)
                        component.songRepository.songsByIds(listOf(song.id)).single()
                    }
                    song = updated
                    component.playerRepo.applyMergedSong(setOf(updated.id), updated)
                    onChanged(setOf(updated.id), updated)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    error = priorityError
                } finally {
                    saving = false
                }
            }
        },
        onSaveCandidates = {},
        isSaving = saving,
        errorMessage = error,
        onSearchOtherSources = if (song.missingOnlineSources().isEmpty()) null else {
            { searching = true }
        },
    )
}
