package com.yellastrodev.dwij.playback

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Позиция и длительность записи, полученные от отдельного проигрывателя. */
data class SourcePreviewProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val canSeek: Boolean = false,
)

/** Хранит прогресс и команды перемотки только для одного запуска прослушивания. */
class SourcePreviewControls {
    private val mutableProgress = MutableStateFlow(SourcePreviewProgress())
    private val seeks = Channel<Long>(Channel.CONFLATED)
    val progress = mutableProgress.asStateFlow()

    /** Принимает позицию в миллисекундах после отпускания ползунка. */
    fun seekTo(positionMs: Long) {
        seeks.trySend(positionMs)
    }

    /** Обновляет позицию и обрабатывает перемотку до окончания или отмены воспроизведения. */
    suspend fun follow(
        finished: Deferred<Unit>,
        readProgress: suspend () -> SourcePreviewProgress,
        seek: suspend (Long) -> Unit,
    ): Unit = coroutineScope {
        val polling = launch {
            while (true) {
                mutableProgress.value = readProgress()
                delay(250)
            }
        }
        val seeking = launch {
            for (requested in seeks) {
                val current = mutableProgress.value
                if (current.canSeek && current.durationMs > 0) {
                    seek(requested.coerceIn(0L, current.durationMs))
                }
            }
        }
        try {
            finished.await()
        } finally {
            polling.cancel()
            seeking.cancel()
        }
    }
}
