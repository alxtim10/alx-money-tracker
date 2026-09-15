package com.alx.moneytracker.data.repository

import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.entity.CategoryEntity
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import com.alx.moneytracker.data.local.entity.WalletEntity
import com.alx.moneytracker.data.local.toDomain
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.domain.logic.DefaultWalletResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [MetadataRepository].
 *
 * Observation delegates to the existing DAO [Flow]s and maps each entity stream to its domain
 * equivalent via Scope 1's `toDomain()` mappers in `com.alx.moneytracker.data.local`, keeping
 * domain models free of Room types and reusing Scope 1 & 2's conventions (AGENTS.md Data Rule 2).
 *
 * The class depends only on the three **metadata** DAOs ([WalletDao], [CategoryDao],
 * [QuickPresetDao]). It has **no `TransactionDao` dependency and no access to the `transactions`
 * table**, so this feature cannot mutate the transactions store under any operation — Data Rule 3
 * and Requirement 15 hold by construction. In particular, [overrideBalance] delegates to the
 * single-row [WalletDao.overrideBalance] update, never a `transactions` insert.
 *
 * Atomic multi-row writes (default assignment, archive-with-reassignment) delegate to the
 * `@Transaction` DAO methods [WalletDao.assignDefault] and [WalletDao.archiveAndReassignDefault]
 * (AGENTS.md Data Rule 1). Every write runs on [ioDispatcher] so DB I/O never blocks the main
 * thread (AGENTS.md Sync Rule 2) and is wrapped in [runCatching] so a thrown exception surfaces as
 * [Result.failure] with the affected store left in its prior state (Requirements 4.7, 6.5, 15.4).
 *
 * @param ioDispatcher dispatcher for suspend writes; defaults to [Dispatchers.IO] and is injectable
 *   for deterministic testing.
 */
class RoomMetadataRepository(
    private val walletDao: WalletDao,
    private val categoryDao: CategoryDao,
    private val quickPresetDao: QuickPresetDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MetadataRepository {

    // region Observation

    override fun observeWallets(): Flow<List<Wallet>> =
        walletDao.observeActiveWallets().map { entities -> entities.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAllActive().map { entities -> entities.map { it.toDomain() } }

    override fun observeQuickPresets(): Flow<List<QuickPreset>> =
        quickPresetDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    // endregion

    // region Wallet writes

    override suspend fun createWallet(wallet: NewWallet): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                // First active wallet becomes the default; otherwise it does not (Requirement 1.5).
                val isFirstActive = walletDao.activeCount() == 0
                walletDao.insert(
                    WalletEntity(
                        name = wallet.name,
                        balance = wallet.balance,
                        isDefault = isFirstActive,
                        isArchived = false
                    )
                )
                Unit
            }
        }

    override suspend fun renameWallet(walletId: Long, name: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching { walletDao.updateName(walletId, name) }
        }

    override suspend fun overrideBalance(walletId: Long, target: Long): Result<Unit> =
        withContext(ioDispatcher) {
            // Single-row balance overwrite; no transactions insert (Data Rule 3, Requirement 4).
            runCatching { walletDao.overrideBalance(walletId, target) }
        }

    override suspend fun archiveWallet(walletId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val target = walletDao.findById(walletId)
                    ?: throw NoSuchElementException("Wallet $walletId not found")

                // Archiving an already-archived wallet is a no-op (Requirement 5.7).
                if (target.isArchived) return@runCatching

                // When archiving the current default, pick a deterministic replacement among the
                // remaining active wallets (Requirements 5.5, 5.6); otherwise no reassignment.
                val replacementId = if (target.isDefault) {
                    // A one-shot snapshot of the current active wallets (Room's Flow emits the
                    // current set immediately) minus the wallet being archived; the deterministic
                    // resolver then picks the replacement default (Requirements 5.5, 5.6).
                    val remainingActive = walletDao.observeActiveWallets().first()
                        .filter { it.id != walletId }
                        .map { it.toDomain() }
                    DefaultWalletResolver.pickReplacementDefault(remainingActive)
                } else {
                    null
                }

                walletDao.archiveAndReassignDefault(walletId, replacementId)
            }
        }

    override suspend fun setDefaultWallet(walletId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val target = walletDao.findById(walletId)
                    ?: throw NoSuchElementException("Wallet $walletId not found")
                if (target.isArchived) {
                    throw IllegalStateException("Cannot set an archived wallet as default")
                }
                // Designating the current default is a no-op (Requirement 6.3).
                if (target.isDefault) return@runCatching

                walletDao.assignDefault(walletId)
            }
        }

    // endregion

    // region Category writes

    override suspend fun createCategory(fields: CategoryFields): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                categoryDao.insert(
                    CategoryEntity(
                        name = fields.name,
                        type = fields.type.name,
                        icon = fields.icon.orEmpty(),
                        isArchived = false
                    )
                )
                Unit
            }
        }

    override suspend fun updateCategory(categoryId: Long, patch: CategoryPatch): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val existing = categoryDao.findById(categoryId)
                    ?: throw NoSuchElementException("Category $categoryId not found")

                // A null patch field means "leave unchanged" (Requirement 9.1). is_archived is
                // never a patch field, so it is always carried over from the existing record.
                val updated = existing.copy(
                    name = patch.name ?: existing.name,
                    type = patch.type?.name ?: existing.type,
                    icon = patch.icon ?: existing.icon
                )
                categoryDao.update(updated)
            }
        }

    override suspend fun archiveCategory(categoryId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val target = categoryDao.findById(categoryId)
                    ?: throw NoSuchElementException("Category $categoryId not found")
                // Archiving an already-archived category is a no-op (Requirement 10.5).
                if (target.isArchived) return@runCatching
                categoryDao.markArchived(categoryId)
            }
        }

    // endregion

    // region Quick Preset writes

    override suspend fun createPreset(fields: PresetFields): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                quickPresetDao.insert(
                    QuickPresetEntity(
                        amount = fields.amount,
                        label = fields.label
                    )
                )
                Unit
            }
        }

    override suspend fun updatePreset(presetId: Long, patch: PresetPatch): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val existing = quickPresetDao.findById(presetId)
                    ?: throw NoSuchElementException("Preset $presetId not found")

                // A null patch field means "leave unchanged" (Requirement 13.1).
                val updated = existing.copy(
                    amount = patch.amount ?: existing.amount,
                    label = patch.label ?: existing.label
                )
                quickPresetDao.update(updated)
            }
        }

    override suspend fun deletePreset(presetId: Long): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching { quickPresetDao.deleteById(presetId) }
        }

    // endregion
}
