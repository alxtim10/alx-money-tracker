package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `quick_presets` table.
 *
 * Presets are observed as a [Flow] ordered by amount so the chip set streams to the UI
 * (Requirements 2.1, 12.1). Scope 3 adds write and hard-delete operations for preset
 * management (Requirements 11.1, 13.1, 14.1). Presets are never referenced by a persisted
 * transaction, so removal is a hard delete rather than a soft archive.
 */
@Dao
interface QuickPresetDao {

    /** Emits all preset chips ordered by ascending amount. */
    @Query("SELECT * FROM quick_presets ORDER BY amount ASC")
    fun observeAll(): Flow<List<QuickPresetEntity>>

    /** Inserts a new preset and returns its generated id (Requirement 11.1). */
    @Insert
    suspend fun insert(preset: QuickPresetEntity): Long

    /** Updates an existing preset in place (Requirement 13.1). */
    @Update
    suspend fun update(preset: QuickPresetEntity)

    /** Hard-deletes the preset with the given [id] (Requirement 14.1). */
    @Query("DELETE FROM quick_presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Returns the preset with the given [id], or null if none exists. */
    @Query("SELECT * FROM quick_presets WHERE id = :id")
    suspend fun findById(id: Long): QuickPresetEntity?
}
