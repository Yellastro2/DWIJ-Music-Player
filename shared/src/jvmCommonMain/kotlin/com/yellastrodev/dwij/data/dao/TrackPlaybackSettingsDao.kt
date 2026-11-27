package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.yellastrodev.dwij.data.entities.TrackPlaybackSettings

/** Хранит ручные отступы независимо от переиндексации источников. */
@Dao
interface TrackPlaybackSettingsDao {
    /** Читает настройки текущей записи, если пользователь уже менял их. */
    @Query("SELECT * FROM track_playback_settings WHERE instanceId = :instanceId")
    suspend fun get(instanceId: String): TrackPlaybackSettings?

    /** Читает настройки для выбранных экземпляров очереди. */
    @Query("SELECT * FROM track_playback_settings WHERE instanceId IN (:instanceIds) AND enabled = 1")
    suspend fun enabledFor(instanceIds: List<String>): List<TrackPlaybackSettings>

    /** Атомарно сохраняет два поля и флаг. */
    @Upsert
    suspend fun save(settings: TrackPlaybackSettings)
}
