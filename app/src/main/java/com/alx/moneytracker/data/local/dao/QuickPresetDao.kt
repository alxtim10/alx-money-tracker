package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `quick_presets` table.
 *
 * Presets are observed as a [Flow] ordered by amount so the chip set streams to the UI
 * (Requirements 2.1, 12.1).
 */
@Dao
interface QuickPresetDao {

    /** Emits all preset chips ordered by ascending amount. */
    @Query("SELECT * FROM quick_presets ORDER BY amount ASC")
    fun observeAll(): Flow<List<QuickPresetEntity>>
}
