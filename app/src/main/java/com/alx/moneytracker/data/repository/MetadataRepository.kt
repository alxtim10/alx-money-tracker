package com.alx.moneytracker.data.repository

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Wallet
import kotlinx.coroutines.flow.Flow

/**
 * Management surface for the metadata entities (Wallets, Categories, Quick Presets) consumed by
 * Scope 1 (Transaction Input Engine) and Scope 2 (Transaction History & Audit).
 *
 * The interface is the structural boundary that keeps Scope 3 out of the `transactions` table:
 * there is deliberately **no** transaction-write method here and, in the Room implementation,
 * **no `TransactionDao` dependency and no access to the `transactions` table**. A
 * `Direct_Balance_Override` ([overrideBalance]) is therefore a single-row balance update that can
 * never record a `Transaction` — the mechanical realization of AGENTS.md Data Rule 3 and
 * Requirement 15, enforced by construction.
 *
 * The three collections are exposed as [Flow]s so a create / update / archive / delete / override
 * propagates live into the consuming scopes with no manual refresh (AGENTS.md Data Rule 2,
 * Requirements 2.4, 8.4, 12.4).
 *
 * Every write returns [Result]: on failure the affected store is left in its prior state and the
 * caller sees [Result.failure] rather than a thrown exception (Requirements 4.7, 6.5, 15.4).
 */
interface MetadataRepository {

    // region Observation (Data Rule 2)

    /** Emits the current collection of Active_Wallet records, re-emitting on any change (Req 2.1, 2.2). */
    fun observeWallets(): Flow<List<Wallet>>

    /** Emits the current collection of Active_Category records, re-emitting on any change (Req 8.1, 8.2). */
    fun observeCategories(): Flow<List<Category>>

    /** Emits the current collection of Quick_Preset records, re-emitting on any change (Req 12.1, 12.2). */
    fun observeQuickPresets(): Flow<List<QuickPreset>>

    // endregion

    // region Wallet writes

    /**
     * Creates a Wallet from a validated [wallet]. The new record's `is_default` is resolved from the
     * active-wallet count: the first active wallet becomes the default, otherwise it does not
     * (Requirement 1.5). `is_archived` is false on creation (Requirement 1.1).
     */
    suspend fun createWallet(wallet: NewWallet): Result<Unit>

    /** Renames the Wallet [walletId] to [name], leaving all other fields unchanged (Requirement 3). */
    suspend fun renameWallet(walletId: Long, name: String): Result<Unit>

    /**
     * Direct Balance Override: overwrites the balance of Wallet [walletId] with [target] via a
     * single-row update. Records **no** `Transaction` (AGENTS.md Data Rule 3, Requirement 4).
     */
    suspend fun overrideBalance(walletId: Long, target: Long): Result<Unit>

    /**
     * Archives (soft-deletes) the Wallet [walletId]. When archiving the current Default_Wallet, the
     * default is atomically reassigned to the deterministically selected replacement among the
     * remaining active wallets (Requirement 5). An already-archived target is a no-op (Requirement 5.7).
     */
    suspend fun archiveWallet(walletId: Long): Result<Unit>

    /**
     * Designates Wallet [walletId] as the single Default_Wallet, atomically clearing any prior
     * default. Rejected when the target does not exist or is archived; designating the current
     * default is a no-op (Requirement 6).
     */
    suspend fun setDefaultWallet(walletId: Long): Result<Unit>

    // endregion

    // region Category writes

    /** Creates a Category from validated [fields] with `is_archived` false (Requirement 7). */
    suspend fun createCategory(fields: CategoryFields): Result<Unit>

    /**
     * Applies a partial [patch] to the Category [categoryId]: only the non-null fields of [patch]
     * change; every omitted field (including `is_archived`) is left unchanged (Requirement 9).
     */
    suspend fun updateCategory(categoryId: Long, patch: CategoryPatch): Result<Unit>

    /** Archives (soft-deletes) the Category [categoryId]; an already-archived target is a no-op (Requirement 10). */
    suspend fun archiveCategory(categoryId: Long): Result<Unit>

    // endregion

    // region Quick Preset writes

    /** Creates a Quick_Preset from validated [fields] (Requirement 11). */
    suspend fun createPreset(fields: PresetFields): Result<Unit>

    /**
     * Applies a partial [patch] to the Quick_Preset [presetId]: only the non-null fields of [patch]
     * change; every omitted field is left unchanged (Requirement 13).
     */
    suspend fun updatePreset(presetId: Long, patch: PresetPatch): Result<Unit>

    /** Hard-deletes the Quick_Preset [presetId], leaving every other preset unchanged (Requirement 14). */
    suspend fun deletePreset(presetId: Long): Result<Unit>

    // endregion
}
