package com.alx.moneytracker.data.repository

import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.entity.CategoryEntity
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import com.alx.moneytracker.data.local.entity.TransactionEntity
import com.alx.moneytracker.data.local.entity.WalletEntity
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Task 4.3 — Unit test for repository error mapping (Requirement 9.5).
 *
 * Verifies that [RoomTransactionRepository.saveTransaction] wraps the atomic DAO write in
 * `runCatching` so a thrown DAO exception surfaces as [Result.failure] (carrying the original
 * cause) rather than escaping the call, and that a non-throwing DAO yields [Result.success].
 *
 * Fake DAOs are named with an `ErrMap` prefix and kept file-private so they do not collide with
 * the fakes defined by task 4.2 (`RepositoryDeltaPropertyTest.kt`) in this same package.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryErrorMappingTest : StringSpec({

    val sampleTransaction = Transaction(
        id = "tx-1",
        timestamp = 1_000L,
        type = TransactionType.EXPENSE,
        amount = 5_000L,
        sourceWalletId = 1L,
        destWalletId = null,
        categoryId = 10L,
        note = "",
        isSynced = false
    )

    fun repositoryWith(transactionDao: TransactionDao): RoomTransactionRepository =
        RoomTransactionRepository(
            transactionDao = transactionDao,
            walletDao = ErrMapWalletDao,
            categoryDao = ErrMapCategoryDao,
            quickPresetDao = ErrMapQuickPresetDao,
            ioDispatcher = UnconfinedTestDispatcher()
        )

    "a thrown DAO exception is mapped to Result.failure with the original cause" {
        val boom = RuntimeException("db failed")
        val repository = repositoryWith(ErrMapThrowingTransactionDao(boom))

        runTest {
            // saveTransaction must not let the exception escape; it returns a Result instead.
            val result = repository.saveTransaction(sampleTransaction)

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
            result.exceptionOrNull().shouldBeInstanceOf<RuntimeException>()
        }
    }

    "a non-throwing DAO yields Result.success" {
        val dao = ErrMapRecordingTransactionDao()
        val repository = repositoryWith(dao)

        runTest {
            val result = repository.saveTransaction(sampleTransaction)

            result.isSuccess shouldBe true
            result.isFailure shouldBe false
            result.getOrNull() shouldBe Unit
            // Sanity: the atomic write was actually attempted.
            dao.insertAndApplyBalancesCalls shouldBe 1
        }
    }
})

/** [TransactionDao] whose atomic write always throws, to exercise the failure branch. */
private class ErrMapThrowingTransactionDao(private val error: Throwable) : TransactionDao {
    override suspend fun insert(tx: TransactionEntity) = throw error

    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override suspend fun applyBalanceDelta(walletId: Long, delta: Long) = throw error

    override suspend fun insertAndApplyBalances(
        tx: TransactionEntity,
        sourceWalletId: Long,
        sourceDelta: Long,
        destWalletId: Long?,
        destDelta: Long
    ) {
        throw error
    }
}

/** [TransactionDao] that records the atomic write without touching a real database. */
private class ErrMapRecordingTransactionDao : TransactionDao {
    var insertAndApplyBalancesCalls: Int = 0
        private set

    override suspend fun insert(tx: TransactionEntity) {
        /* no-op */
    }

    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override suspend fun applyBalanceDelta(walletId: Long, delta: Long) {
        /* no-op */
    }

    override suspend fun insertAndApplyBalances(
        tx: TransactionEntity,
        sourceWalletId: Long,
        sourceDelta: Long,
        destWalletId: Long?,
        destDelta: Long
    ) {
        insertAndApplyBalancesCalls++
    }
}

/** Trivial observation-only fakes; error mapping does not exercise these streams. */
private object ErrMapWalletDao : WalletDao {
    override fun observeActiveWallets(): Flow<List<WalletEntity>> = flowOf(emptyList())
    override fun observeDefaultWallet(): Flow<WalletEntity?> = flowOf(null)
}

private object ErrMapCategoryDao : CategoryDao {
    override fun observeByType(type: String): Flow<List<CategoryEntity>> = flowOf(emptyList())
    override fun observeAllActive(): Flow<List<CategoryEntity>> = flowOf(emptyList())
}

private object ErrMapQuickPresetDao : QuickPresetDao {
    override fun observeAll(): Flow<List<QuickPresetEntity>> = flowOf(emptyList())
}
