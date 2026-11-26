package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.playback.SourcePreviewControls
import com.yellastrodev.dwij.playback.SourcePreviewProgress
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Воспроизводит и перематывает источник в отдельном проигрывателе JavaFX. */
internal suspend fun playDesktopSourcePreview(uri: String, controls: SourcePreviewControls) {
    val finished = CompletableDeferred<Unit>()
    val player = JavaFxRuntime.call { MediaPlayer(Media(uri)) }
    try {
        JavaFxRuntime.call {
            player.setOnEndOfMedia { finished.complete(Unit) }
            player.setOnError {
                finished.completeExceptionally(player.error ?: IllegalStateException("Ошибка аудио"))
            }
            player.play()
        }
        controls.follow(
            finished = finished,
            readProgress = {
                JavaFxRuntime.call {
                    val duration = player.totalDuration?.toMillis()
                        ?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L
                    SourcePreviewProgress(
                        positionMs = player.currentTime.toMillis().toLong().coerceAtLeast(0L),
                        durationMs = duration,
                        canSeek = duration > 0,
                    )
                }
            },
            seek = { positionMs -> JavaFxRuntime.call { player.seek(Duration.millis(positionMs.toDouble())) } },
        )
    } finally {
        withContext(NonCancellable) { JavaFxRuntime.call { player.dispose() } }
    }
}
