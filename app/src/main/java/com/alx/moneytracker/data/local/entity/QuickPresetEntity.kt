package com.alx.moneytracker.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the `quick_presets` table.
 *
 * Each row represents a tappable preset chip that adds a fixed value to the running amount
 * (Requirement 2).
 *
 * - id: auto-generated primary key.
 * - amount: fixed value added on tap, in the smallest currency unit.
 * - label: display label for the chip (for example, "+10.000").
 */
@Entity(tableName = "quick_presets")
data class QuickPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val label: String
)
