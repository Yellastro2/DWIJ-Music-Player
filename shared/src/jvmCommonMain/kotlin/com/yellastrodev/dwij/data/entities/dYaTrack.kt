package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yellastrodev.yamusicsdk.entities.YaTrack
import androidx.room.Ignore
import kotlin.math.roundToLong

/** Сохранённые метаданные, внешние точки фейда и время проверки доступности записи Яндекса. */
@Entity(tableName = "tracks")
class dYaTrack(
    @PrimaryKey
    val id: String,
    val title: String,
    val available: Boolean,
    val availabilityCheckedAt: Long = 0L,
    val ogImageUri: String? = null,
    val coverUri: String? = null,
    val durationMs: Int? = null,
    val previewDurationMs: Int? = null,
    val storageDir: String? = null,
    val fileSize: Int? = null,
    val trackSource: String? = null,
    val fadeStartMs: Long? = null,
    val fadeEndMs: Long? = null,
//    val availableForPremiumUsers: Boolean? = null,
//    val availableFullWithoutPermission: Boolean? = null,
//    val availableForOptions: List<Options> = listOf(),
//    val r128: R128? = null,
//    val major: Major? = null,
//    val lyricsAvailable: Boolean? = null,
//    val lyricsInfo: LyricsInfo? = null,
//    val derivedColors: DerivedColors? = null,
//    val type: AlbumType? = null,
//    val rememberPosition: Boolean? = null,
//    val trackSharingFlag: TrackSharingFlag? = null,
//    val contentWarning: String? = null
) {

    fun getCoverUriAny() = ogImageUri ?: coverUri

    @Ignore
    var artists: List<dYaArtist> = emptyList()
    @Ignore
    var albums: List<dYaAlbum> = emptyList()

    @Ignore
    var playlists: List<String> = emptyList()

}

@Entity(tableName = "albums")
data class dYaAlbum(
    @PrimaryKey val id: Int,
    val title: String
)

@Entity(
    tableName = "track_albums",
    primaryKeys = ["trackId", "albumId"],
    foreignKeys = [
        ForeignKey(
            entity = dYaTrack::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = dYaAlbum::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("albumId")]
)
data class dTrackAlbumCrossRef(
    val trackId: String,
    val albumId: Int
)

@Entity(tableName = "artists")
data class dYaArtist(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
     val id: Int? = null,
     val name: String
)

@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "artistLocalId"],
    foreignKeys = [
        ForeignKey(
            entity = dYaTrack::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = dYaArtist::class,
            parentColumns = ["localId"], // <-- меняем на localId
            childColumns = ["artistLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("artistLocalId")]
)
data class dTrackArtistCrossRef(
    val trackId: String,
    val artistLocalId: Long
)

/** Переносит метки фейда и сохраняет пустое название как признак неполной записи. */
fun YaTrack.toEntity(availabilityCheckedAt: Long = System.currentTimeMillis()): dYaTrack =
    dYaTrack(
        id = id,
        title = title,
        available = available && title.isNotBlank(),
        availabilityCheckedAt = availabilityCheckedAt,
        durationMs = durationMs,
        previewDurationMs = previewDurationMs,
        storageDir = storageDir,
        fadeStartMs = fade?.inStart.toFadePositionMs(),
        fadeEndMs = fade?.outStop.toFadePositionMs(),
        fileSize = fileSize,
        trackSource = trackSource,
        ogImageUri = ogImageUri,
        coverUri = coverUri
    ).apply {
        this.artists = this@toEntity.artists.map { dYaArtist(id=it.id, name=it.name) }
        this.albums = this@toEntity.albums.map { dYaAlbum(it.id, it.title) }
    }

/** Сохраняет известные точки fade и остальную метадату, если ответ о треке неполный. */
internal fun dYaTrack.withStoredMetadata(previous: dYaTrack?): dYaTrack {
    val incomplete = title.isBlank()
    val fallback = previous.takeIf { incomplete }
    val savedFadeStart = fadeStartMs ?: previous?.fadeStartMs
    val savedFadeEnd = fadeEndMs ?: previous?.fadeEndMs
    if (!incomplete && savedFadeStart == fadeStartMs && savedFadeEnd == fadeEndMs) return this
    return dYaTrack(
        id = id,
        title = if (incomplete) fallback?.title?.takeIf { it.isNotBlank() } ?: "Недоступный трек" else title,
        available = available && !incomplete,
        availabilityCheckedAt = availabilityCheckedAt,
        ogImageUri = fallback?.ogImageUri ?: ogImageUri,
        coverUri = fallback?.coverUri ?: coverUri,
        durationMs = fallback?.durationMs ?: durationMs,
        previewDurationMs = fallback?.previewDurationMs ?: previewDurationMs,
        storageDir = fallback?.storageDir ?: storageDir,
        fileSize = fallback?.fileSize ?: fileSize,
        trackSource = fallback?.trackSource ?: trackSource,
        fadeStartMs = savedFadeStart,
        fadeEndMs = savedFadeEnd,
    ).apply {
        artists = fallback?.artists?.takeIf { it.isNotEmpty() } ?: this@withStoredMetadata.artists
        albums = fallback?.albums?.takeIf { it.isNotEmpty() } ?: this@withStoredMetadata.albums
        playlists = if (incomplete) fallback?.playlists.orEmpty() else this@withStoredMetadata.playlists
    }
}

/** Переводит доступные, конечные позиции Яндекса в миллисекунды. */
private fun Double?.toFadePositionMs(): Long? =
    this?.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE / 1000.0 }
        ?.times(1000)?.roundToLong()
