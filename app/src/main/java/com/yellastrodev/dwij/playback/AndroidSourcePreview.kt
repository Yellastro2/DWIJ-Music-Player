package com.yellastrodev.dwij.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Проигрывает один источник и обрабатывает перемотку в отдельном ExoPlayer. */
internal suspend fun playAndroidSourcePreview(
    context: Context,
    uri: String,
    hls: Boolean,
    controls: SourcePreviewControls,
) =
    withContext(Dispatchers.Main.immediate) {
        val finished = CompletableDeferred<Unit>()
        val player = ExoPlayer.Builder(context).build()
        try {
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finished.complete(Unit)
                }

                override fun onPlayerError(error: PlaybackException) {
                    finished.completeExceptionally(error)
                }
            })
            val media = MediaItem.Builder().setUri(uri)
            if (hls) media.setMimeType(MimeTypes.APPLICATION_M3U8)
            player.setMediaItem(media.build())
            player.prepare()
            player.play()
            controls.follow(
                finished = finished,
                readProgress = {
                    SourcePreviewProgress(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.coerceAtLeast(0L),
                        canSeek = player.isCurrentMediaItemSeekable,
                    )
                },
                seek = { positionMs -> player.seekTo(positionMs) },
            )
        } finally {
            player.release()
        }
    }
