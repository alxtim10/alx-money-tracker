package com.alx.moneytracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the `categories` table.
 *
 * - id: auto-generated primary key.
 * - name: display name of the category (for example, "Makan").
 * - type: [com.alx.moneytracker.domain.TransactionType] name the category applies to (Requirements 5.2/5.3).
 * - icon: icon identifier for display.
 * - isArchived: excludes the category from selectable lists when true.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val icon: String,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false
)
