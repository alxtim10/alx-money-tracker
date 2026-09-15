package com.alx.moneytracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.alx.moneytracker.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the `categories` table.
 *
 * Categories are observed as a [Flow] filtered to a transaction type so the selector reflects the
 * selected type in real time (Requirements 5.2, 5.3, 12.1).
 *
 * Scope 3 (Customization & Metadata) adds create / update / archive management methods here. None
 * of these methods reference the `transactions` table — archiving is a soft delete so historical
 * [com.alx.moneytracker.domain.Transaction] records that reference a category stay valid
 * (Requirements 7.1, 9.1, 10.1).
 */
@Dao
interface CategoryDao {

    /**
     * Emits the non-archived categories whose [type] matches the given
     * [com.alx.moneytracker.domain.TransactionType] name.
     */
    @Query("SELECT * FROM categories WHERE type = :type AND is_archived = 0")
    fun observeByType(type: String): Flow<List<CategoryEntity>>

    /**
     * Emits all non-archived categories across every transaction type as a reactive [Flow],
     * used to build the Scope 2 History category filter chips (Requirement 5.1).
     */
    @Query("SELECT * FROM categories WHERE is_archived = 0")
    fun observeAllActive(): Flow<List<CategoryEntity>>

    /**
     * Persists a new category, returning the auto-generated row id (Requirement 7.1).
     */
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    /**
     * Persists updated field values on an existing category, matched by primary key (Requirement 9.1).
     */
    @Update
    suspend fun update(category: CategoryEntity)

    /**
     * Returns the category with the given [id], or null when no such row exists.
     */
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun findById(id: Long): CategoryEntity?

    /**
     * Archives a category (soft delete): sets its `is_archived` flag to true while retaining the
     * row so historical transactions that reference it stay valid (Requirement 10.1).
     */
    @Query("UPDATE categories SET is_archived = 1 WHERE id = :id")
    suspend fun markArchived(id: Long)
}
