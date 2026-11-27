package com.yellastrodev.dwij.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import com.yellastrodev.dwij.utils.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Публикует состояние, artwork и feedback; восстановлением ошибок владеет AndroidPlaybackRecovery. */
class AndroidPlayerListener(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val stateStore: PlaybackStateStore,
    private val feedback: AndroidPlaybackFeedbackAdapter,
    private val trackCoverLoader: TrackCoverLoader,
) : Player.Listener {

    private var progressJob: Job? = null

    /** Публикует смену записи и передаёт в статистику исходные координаты файла. */
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        feedback.onMediaItemTransition(
            mediaItem = mediaItem,
            reason = reason,
            isPlaying = player.isPlaying,
            currentPositionMs = player.filePositionMs,
            durationMs = player.fileDurationMs,
        )

        if (
            mediaItem != null &&
            player.currentMediaItemIndex != C.INDEX_UNSET
        ) {
            stateStore.setCurrentIndex(player.currentMediaItemIndex)

            if (player.playbackState == Player.STATE_READY) {
                stateStore.completeTrackChange()
            }
        }

        if (mediaItem == null) return

        val isLocal = mediaItem.mediaMetadata.extras
            ?.getString(PlaybackMetadataKeys.MUSIC_SOURCE) ==
                PlaybackMetadataKeys.SOURCE_LOCAL

        if (
            isLocal ||
            mediaItem.mediaMetadata.artworkData != null ||
            mediaItem.mediaMetadata.artworkUri != null
        ) {
            return
        }

        loadArtwork(mediaItem)
    }

    /** Публикует конец очереди по порядку Media3, включая конец shuffle. */
    override fun onPlaybackStateChanged(playbackState: Int) {
        stateStore.setPlayback(
            isPlaying = player.isPlaying,
            currentIndex = player.currentMediaItemIndex,
        )

        if (playbackState == Player.STATE_READY) {
            stateStore.completeTrackChange()
        }

        if (playbackState != Player.STATE_ENDED) return

        feedback.onPlaybackEnded(
            currentPositionMs = player.filePositionMs,
            durationMs = player.fileDurationMs,
            completed = true,
        )

        val playlistFinished =
            player.mediaItemCount > 0 && !player.hasNextMediaItem() &&
                    player.repeatMode == Player.REPEAT_MODE_OFF

        if (playlistFinished) {
            scope.launch {
                stateStore.emit(PlayerEvent.TrackListEnd("Playlist finished"))
            }
        } else {
            // Не ходим обратно через PlayerRepository -> PlayerEngine -> PlayerService.
            player.seekToNext()
        }
    }

    /** Сохраняет флаг воспроизведения и полную шкалу для статистики источника. */
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        stateStore.setPlaying(isPlaying)

        feedback.onIsPlayingChanged(
            mediaItem = player.currentMediaItem,
            isPlaying = isPlaying,
            currentPositionMs = player.filePositionMs,
            durationMs = player.fileDurationMs,
        )

        if (isPlaying) startProgressUpdates() else stopProgressUpdates()
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        stateStore.setWantsToPlay(playWhenReady)
    }

    /** Сохраняет диагностику; повтор/пропуск выполняет recovery без диалогов об ошибке. */
    override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "[onPlayerError] Ошибка Media3, код=${error.errorCode}; восстановление выполняет AndroidPlaybackRecovery")
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        stateStore.setShuffle(shuffleModeEnabled)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        stateStore.setRepeatAll(repeatMode == Player.REPEAT_MODE_ALL)
    }

    fun release() {
        stopProgressUpdates()
    }

    /** Дополняет именно текущий MediaItem, сохраняя обновлённые во время загрузки границы. */
    private fun loadArtwork(mediaItem: MediaItem) {
        val trackId = mediaItem.mediaId

        scope.launch(Dispatchers.IO) {
            val coverBytes =
                trackCoverLoader.loadPlayerCover(trackId) ?: return@launch

            withContext(Dispatchers.Main.immediate) {
                val index = player.currentMediaItemIndex
                if (index == C.INDEX_UNSET) return@withContext
                val current = player.getMediaItemAt(index)
                if (current.mediaId != trackId) return@withContext

                player.replaceMediaItem(index, current.buildUpon()
                    .setMediaMetadata(current.mediaMetadata.buildUpon()
                        .setArtworkData(coverBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build())
                    .build())
            }
        }
    }

    /** Обновляет шкалу после перемотки или изменения диапазона даже на паузе. */
    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_PLAYBACK_STATE_CHANGED) && player.duration > 0
        ) {
            stateStore.setProgress(player.currentPosition, player.duration)
        }
    }

    /** Публикует прогресс фрагмента для UI и исходные координаты для статистики. */
    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val positionMs = player.currentPosition
                val durationMs = player.duration

                stateStore.setProgress(positionMs, durationMs)

                feedback.onProgress(
                    trackId = player.currentMediaItem?.mediaId,
                    currentPositionMs = player.filePositionMs,
                    durationMs = player.fileDurationMs,
                )

                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private companion object {
        const val TAG = "AndroidPlayerListener"
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
