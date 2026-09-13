package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.alx.moneytracker.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `categories` table.
 *
 * Categories are observed as a [Flow] filtered to a transaction type so the selector reflects the
 * selected type in real time (Requirements 5.2, 5.3, 12.1).
 */
@Dao
interface CategoryDao {

    /**
     * Emits the non-archived categories whose [type] matches the given
     * [com.alx.moneytracker.domain.TransactionType] name.
     */
    @Query("SELECT * FROM categories WHERE type = :type AND is_archived = 0")
    fun observeByType(type: String): Flow<List<CategoryEntity>>
}
