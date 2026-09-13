package com.alx.moneytracker.data.repository

import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.toDomain
import com.alx.moneytracker.data.local.toEntity
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [TransactionRepository].
 *
 * Observation delegates directly to the DAO [Flow]s and maps each entity stream to its domain
 * equivalent via the mappers in `com.alx.moneytracker.data.local`, keeping domain models free of
 * Room types (Requirement 12.1, AGENTS.md Data Rule 2).
 *
 * [saveTransaction] computes the balance deltas from the transaction type and delegates to the
 * single `@Transaction` DAO method [TransactionDao.insertAndApplyBalances] (the atomic boundary,
 * AGENTS.md Data Rule 1). The database call runs on [ioDispatcher] so the main thread is never
 * blocked for DB I/O (AGENTS.md Sync Rule 2), and is wrapped in [runCatching] so a rollback surfaces
 * as [Result.failure] instead of a thrown exception (Requirement 9.5).
 *
 * @param ioDispatcher dispatcher for the atomic write; defaults to [Dispatchers.IO] and is injectable
 *   for deterministic testing.
 */
class RoomTransactionRepository(
    private val transactionDao: TransactionDao,
    private val walletDao: WalletDao,
    private val categoryDao: CategoryDao,
    private val quickPresetDao: QuickPresetDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TransactionRepository {

    override fun observeWallets(): Flow<List<Wallet>> =
        walletDao.observeActiveWallets().map { entities -> entities.map { it.toDomain() } }

    override fun observeCategories(type: TransactionType): Flow<List<Category>> =
        categoryDao.observeByType(type.name).map { entities -> entities.map { it.toDomain() } }

    override fun observeQuickPresets(): Flow<List<QuickPreset>> =
        quickPresetDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeDefaultWallet(): Flow<Wallet?> =
        walletDao.observeDefaultWallet().map { it?.toDomain() }

    override suspend fun saveTransaction(transaction: Transaction): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val (sourceDelta, destWalletId, destDelta) = deltasFor(transaction)
                transactionDao.insertAndApplyBalances(
                    tx = transaction.toEntity(),
                    sourceWalletId = transaction.sourceWalletId,
                    sourceDelta = sourceDelta,
                    destWalletId = destWalletId,
                    destDelta = destDelta
                )
            }
        }

    /**
     * Derives the balance deltas for [transaction] (Requirements 9.2, 9.3, 9.4):
     * - EXPENSE: source `-amount`, no destination.
     * - INCOME: source `+amount`, no destination.
     * - TRANSFER: source `-amount`, destination `+amount`.
     */
    private fun deltasFor(transaction: Transaction): Deltas = when (transaction.type) {
        TransactionType.EXPENSE -> Deltas(
            sourceDelta = -transaction.amount,
            destWalletId = null,
            destDelta = 0L
        )
        TransactionType.INCOME -> Deltas(
            sourceDelta = transaction.amount,
            destWalletId = null,
            destDelta = 0L
        )
        TransactionType.TRANSFER -> Deltas(
            sourceDelta = -transaction.amount,
            destWalletId = transaction.destWalletId,
            destDelta = transaction.amount
        )
    }

    /** Balance change to apply to the source and (for TRANSFER) destination wallet. */
    private data class Deltas(
        val sourceDelta: Long,
        val destWalletId: Long?,
        val destDelta: Long
    )
}
