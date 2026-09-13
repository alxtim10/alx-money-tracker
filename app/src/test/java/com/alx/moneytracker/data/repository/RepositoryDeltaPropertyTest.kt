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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Property-based tests for the repository's balance-delta logic (Task 4.2).
 *
 * These exercise the real [RoomTransactionRepository.saveTransaction] path (delta derivation +
 * the DAO's atomic `insertAndApplyBalances`) against an in-memory **fake** [TransactionDao] rather
 * than a Room database, so the tests run as plain JVM unit tests (no emulator required). The design
 * explicitly permits "a fake DAO" as the target for Properties 8 and 9.
 *
 * The fake keeps a `walletId -> balance` map and applies signed deltas exactly as the production
 * SQL `UPDATE wallets SET balance = balance + :delta` would, invoking the interface's own default
 * [TransactionDao.insertAndApplyBalances] body so the atomic sequencing under test is the real one.
 *
 * Each property runs a minimum of 100 iterations with kotest-property. Amounts and balances are
 * constrained to a safe range so no arithmetic overflows the oracle.
 *
 * Validates: Requirements 9.2, 9.3, 9.4
 */
class RepositoryDeltaPropertyTest : FunSpec({

    // Safe ranges: two balances plus one amount stay well within Long bounds and never overflow.
    val balances: Arb<Long> = Arb.long(0L..1_000_000_000L)
    val amounts: Arb<Long> = Arb.long(1L..1_000_000_000L)
    val types: Arb<TransactionType> = Arb.of(TransactionType.entries)

    fun repositoryWith(dao: InMemoryTransactionDao): RoomTransactionRepository =
        RoomTransactionRepository(
            transactionDao = dao,
            walletDao = StubWalletDao,
            categoryDao = StubCategoryDao,
            quickPresetDao = StubQuickPresetDao,
            ioDispatcher = UnconfinedTestDispatcher()
        )

    fun transaction(
        type: TransactionType,
        amount: Long,
        sourceWalletId: Long,
        destWalletId: Long?
    ): Transaction = Transaction(
        id = "tx-1",
        timestamp = 0L,
        type = type,
        amount = amount,
        sourceWalletId = sourceWalletId,
        destWalletId = destWalletId,
        categoryId = 1L,
        note = "",
        isSynced = false
    )

    // Wallet ids used throughout: 1 = source, 2 = destination, 3 = unrelated bystander.
    val sourceId = 1L
    val destId = 2L
    val otherId = 3L

    // Feature: transaction-input-engine, Property 8
    test("Property 8: Balance deltas are correct for each transaction type") {
        checkAll(
            PropTestConfig(iterations = 100),
            types,
            amounts,
            balances,
            balances,
            balances
        ) { type, amount, sourceStart, destStart, otherStart ->
            val dao = InMemoryTransactionDao(
                mutableMapOf(sourceId to sourceStart, destId to destStart, otherId to otherStart)
            )
            val repository = repositoryWith(dao)
            val destWalletId = if (type == TransactionType.TRANSFER) destId else null

            runTest {
                val result = repository.saveTransaction(
                    transaction(type, amount, sourceId, destWalletId)
                )
                result.isSuccess shouldBe true
            }

            // Source moves by -amount for EXPENSE/TRANSFER and +amount for INCOME.
            val expectedSource = when (type) {
                TransactionType.EXPENSE, TransactionType.TRANSFER -> sourceStart - amount
                TransactionType.INCOME -> sourceStart + amount
            }
            dao.balanceOf(sourceId) shouldBe expectedSource

            // Destination moves by +amount only for TRANSFER; otherwise it is untouched.
            val expectedDest = if (type == TransactionType.TRANSFER) destStart + amount else destStart
            dao.balanceOf(destId) shouldBe expectedDest

            // A wallet not involved in the transaction never changes.
            dao.balanceOf(otherId) shouldBe otherStart

            // The transaction log row was recorded exactly once.
            dao.insertedCount() shouldBe 1
        }
    }

    // Feature: transaction-input-engine, Property 9
    test("Property 9: Transfers conserve combined balance") {
        checkAll(
            PropTestConfig(iterations = 100),
            amounts,
            balances,
            balances
        ) { amount, sourceStart, destStart ->
            val dao = InMemoryTransactionDao(
                mutableMapOf(sourceId to sourceStart, destId to destStart)
            )
            val repository = repositoryWith(dao)

            runTest {
                val result = repository.saveTransaction(
                    transaction(TransactionType.TRANSFER, amount, sourceId, destId)
                )
                result.isSuccess shouldBe true
            }

            // Money only moves between the two wallets, so their combined total is invariant.
            val combinedBefore = sourceStart + destStart
            val combinedAfter = dao.balanceOf(sourceId) + dao.balanceOf(destId)
            combinedAfter shouldBe combinedBefore
        }
    }
})

/**
 * In-memory fake of [TransactionDao].
 *
 * [applyBalanceDelta] mutates the balance map exactly like the production `UPDATE ... balance +
 * :delta` query; [insert] records the row. [insertAndApplyBalances] is inherited from the interface
 * so the real atomic sequencing (insert, then source delta, then optional dest delta) is exercised.
 */
private class InMemoryTransactionDao(
    private val balances: MutableMap<Long, Long>
) : TransactionDao {

    private val inserted = mutableListOf<TransactionEntity>()

    override suspend fun insert(tx: TransactionEntity) {
        inserted.add(tx)
    }

    override suspend fun applyBalanceDelta(walletId: Long, delta: Long) {
        balances[walletId] = (balances[walletId] ?: 0L) + delta
    }

    fun balanceOf(walletId: Long): Long = balances.getValue(walletId)

    fun insertedCount(): Int = inserted.size
}

/** Trivial DAO stubs: observation is not exercised by the delta properties. */
private object StubWalletDao : WalletDao {
    override fun observeActiveWallets(): Flow<List<WalletEntity>> = flowOf(emptyList())
    override fun observeDefaultWallet(): Flow<WalletEntity?> = flowOf(null)
}

private object StubCategoryDao : CategoryDao {
    override fun observeByType(type: String): Flow<List<CategoryEntity>> = flowOf(emptyList())
}

private object StubQuickPresetDao : QuickPresetDao {
    override fun observeAll(): Flow<List<QuickPresetEntity>> = flowOf(emptyList())
}
