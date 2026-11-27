package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Пользовательские настройки записи. Null оставляет значение, предложенное источником. */
@Entity(tableName = "track_playback_settings")
data class TrackPlaybackSettings(
    @PrimaryKey val instanceId: String,
    val enabled: Boolean = false,
    val skipStartMs: Long? = null,
    val skipEndMs: Long? = null,
)

/** Миллисекунды, пропускаемые с двух сторон исходной записи. */
data class PlaybackOffsets(val startMs: Long = 0, val endMs: Long = 0) {
    /** Проверяет, что известный диапазон оставляет место для воспроизведения. */
    fun fits(durationMs: Long?): Boolean =
        startMs >= 0 && endMs >= 0 &&
            (durationMs == null || (endMs < durationMs && startMs < durationMs - endMs))

    /** Возвращает границы относительно начала файла для активного допустимого диапазона. */
    fun range(durationMs: Long?): PlaybackRange? {
        if (this == PlaybackOffsets() || !fits(durationMs)) return null
        if (endMs > 0 && durationMs == null) return null
        return PlaybackRange(startMs, durationMs?.minus(endMs)?.takeIf { endMs > 0 })
    }
}

/** Конец — абсолютная позиция в файле. Null означает воспроизведение до конца файла. */
data class PlaybackRange(val startMs: Long, val endMs: Long?) {
    /** Преобразует исходную позицию при смене диапазона без отрицательной перемотки. */
    fun relativePosition(filePositionMs: Long): Long =
        ((endMs?.let(filePositionMs::coerceAtMost) ?: filePositionMs) - startMs).coerceAtLeast(0)
}

/** Подставляет внешние точки Яндекса независимо для каждого поля. */
fun PlaybackTrack.offsetsFor(settings: TrackPlaybackSettings): PlaybackOffsets {
    val duration = durationMs?.takeIf { it > 0 }
    val fadeStart = yandexTrack?.fadeStartMs?.takeIf { duration == null || it < duration } ?: 0
    val fadeEnd = yandexTrack?.fadeEndMs?.takeIf { duration != null && it > fadeStart && it <= duration }
    return PlaybackOffsets(
        startMs = settings.skipStartMs ?: fadeStart,
        endMs = settings.skipEndMs ?: if (duration != null && fadeEnd != null) duration - fadeEnd else 0,
    )
}
