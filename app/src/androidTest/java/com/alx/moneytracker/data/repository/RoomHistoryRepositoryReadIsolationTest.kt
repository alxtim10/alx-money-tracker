package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.toEntity
import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.TimeFilter
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.logic.SummaryAggregator
import com.alx.moneytracker.domain.logic.TransactionFilter
import com.alx.moneytracker.domain.logic.TransactionSorter
import com.alx.moneytracker.ui.history.toRows
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **read isolation** (Task 9.2, Property 9).
 *
 * ```
 * // Feature: transaction-history-audit, Property 9: Reading, filtering, sorting, and aggregating never mutate the store
 * ```
 *
 * *For any* `Transactions_Store` contents and *any* sequence of read, filter, sort, and
 * aggregation operations, the count of `Transaction` records and the field values of every
 * `Transaction` record in the store after the sequence are identical to their values before the
 * sequence (Requirements 12.1, 12.2, 12.3).
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Transactions_Store` (the Room `transactions` table), so
 * it must run against an actual Room database rather than a fake. Following the conventions of the
 * existing `TransactionDaoObserveAllTest` / `TransactionDaoAtomicityTest` (Scope 1's Room tests),
 * it builds a real [AppDatabase] with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries], runs suspend/Flow calls under
 * [runBlocking] on the test thread, and drives every seed insert through Scope 1's write path
 * ([TransactionDao.insertAndApplyBalances]).
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`), so this test generates its inputs with a seeded
 * [kotlin.random.Random]. It runs [ITERATIONS] independent cases; each case seeds a fresh store
 * with a randomly generated set of transactions, then runs a randomly generated sequence of
 * read/filter/sort/aggregate operations. The seeded RNG makes any failure deterministically
 * reproducible from the printed seed.
 *
 * ### Read sequence exercised
 * Each case performs, against the same seeded store:
 * - repeated reads via [RoomHistoryRepository.observeTransactions] / observeWallets /
 *   observeCategories,
 * - [TransactionFilter.apply] with a randomly activated [FilterSet],
 * - [TransactionSorter.sort] across all four [SortOrder] values,
 * - [SummaryAggregator.aggregate] over the filtered set,
 * - row mapping via [toRows].
 *
 * ### Snapshot comparison
 * Before and after the read sequence, the store is snapshotted by reading every row of the
 * `transactions` table directly via raw SQL (bypassing the repository, so the assertion does not
 * depend on the code under test). The snapshot captures every field of every row. The test asserts
 * the row count is unchanged and every field of every row is identical.
 */
@RunWith(AndroidJUnit4::class)
class RoomHistoryRepositoryReadIsolationTest {

    private lateinit var db: AppDatabase
    private lateinit var transactionDao: TransactionDao
    private lateinit var repository: RoomHistoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transactionDao = db.transactionDao()
        repository = RoomHistoryRepository(
            transactionDao = transactionDao,
            walletDao = db.walletDao(),
            categoryDao = db.categoryDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A full snapshot of one `transactions` row; equality covers every field. */
    private data class TxRow(
        val id: String,
        val timestamp: Long,
        val type: String,
        val amount: Long,
        val sourceWalletId: Long,
        val destWalletId: Long?,
        val categoryId: Long,
        val note: String,
        val isSynced: Int
    )

    /** Inserts a wallet directly via raw SQL and returns its generated row id. */
    private fun insertWallet(name: String, balance: Long): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO wallets (name, balance, is_default, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, balance, 0, 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Inserts a category directly via raw SQL and returns its generated row id. */
    private fun insertCategory(name: String, type: TransactionType): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO categories (name, type, icon, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, type.name, "icon", 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /**
     * Reads the entire `transactions` table directly (raw SQL, not the repository) into a stable,
     * order-independent snapshot keyed by id.
     */
    private fun snapshotStore(): Map<String, TxRow> {
        val rows = mutableMapOf<String, TxRow>()
        db.openHelper.writableDatabase.query(
            "SELECT id, timestamp, type, amount, source_wallet, dest_wallet, category_id, note, is_synced FROM transactions"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                rows[id] = TxRow(
                    id = id,
                    timestamp = cursor.getLong(1),
                    type = cursor.getString(2),
                    amount = cursor.getLong(3),
                    sourceWalletId = cursor.getLong(4),
                    destWalletId = if (cursor.isNull(5)) null else cursor.getLong(5),
                    categoryId = cursor.getLong(6),
                    note = cursor.getString(7),
                    isSynced = cursor.getInt(8)
                )
            }
        }
        return rows
    }

    /**
     * Property 9 — reading, filtering, sorting, and aggregating never mutate the store.
     *
     * Runs [ITERATIONS] randomized cases. Each case:
     * 1. seeds a fresh store with a random set of transactions (via Scope 1's write path),
     * 2. snapshots every row/field,
     * 3. runs a random read/filter/sort/aggregate sequence through the repository + pure logic,
     * 4. re-snapshots and asserts the count and every field are unchanged.
     */
    @Test
    fun readingFilteringSortingAggregating_neverMutatesTheStore() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runReadIsolationCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 9 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            // Reset the store between cases so each case starts from a clean, independently
            // generated state.
            clearStore()
        }
    }

    private fun runReadIsolationCase(rng: Random) = runBlocking {
        // --- Generate a small pool of wallets and categories to reference. ---
        val walletIds = (0 until rng.nextInt(1, 4)).map { i ->
            insertWallet(name = "W$i", balance = 1_000_000L)
        }
        val categoryIds = (0 until rng.nextInt(1, 4)).map { i ->
            insertCategory(name = "C$i", type = randomType(rng))
        }

        // --- Seed a random set of transactions via Scope 1's atomic write path. ---
        val count = rng.nextInt(0, 12)
        repeat(count) { i ->
            val type = randomType(rng)
            val amount = rng.nextLong(0, 500_000)
            val source = walletIds.random(rng)
            val dest = if (type == TransactionType.TRANSFER) {
                walletIds.random(rng)
            } else {
                null
            }
            val tx = Transaction(
                id = "tx-${i}-${rng.nextLong()}",
                timestamp = rng.nextLong(0, 10_000_000),
                type = type,
                amount = amount,
                sourceWalletId = source,
                destWalletId = dest,
                categoryId = categoryIds.random(rng),
                note = if (rng.nextBoolean()) "note-$i" else "",
                isSynced = rng.nextBoolean()
            )
            val (sourceDelta, destDelta) = when (type) {
                TransactionType.EXPENSE -> -amount to 0L
                TransactionType.INCOME -> amount to 0L
                TransactionType.TRANSFER -> -amount to amount
            }
            transactionDao.insertAndApplyBalances(
                tx = tx.toEntity(),
                sourceWalletId = source,
                sourceDelta = sourceDelta,
                destWalletId = dest,
                destDelta = destDelta
            )
        }

        // --- Snapshot BEFORE the read sequence. ---
        val before = snapshotStore()

        // --- Random read / filter / sort / aggregate sequence. ---
        val operations = rng.nextInt(1, 6)
        repeat(operations) {
            val transactions: List<Transaction> =
                repository.observeTransactions().first().getOrThrow()
            val wallets = repository.observeWallets().first()
            val categories = repository.observeCategories().first()

            val filters = randomFilterSet(rng, walletIds, categoryIds)
            val filtered = TransactionFilter.apply(transactions, filters)

            val order = SortOrder.entries.toTypedArray().random(rng)
            val sorted = TransactionSorter.sort(filtered, order)

            // Aggregate and map rows; results are read-only projections.
            SummaryAggregator.aggregate(filtered)
            sorted.toRows(wallets, categories)
        }

        // --- Snapshot AFTER; assert count and every field unchanged. ---
        val after = snapshotStore()

        assertEquals(
            "Read sequence changed the transaction row count",
            before.size,
            after.size
        )
        assertEquals(
            "Read sequence changed the set of transaction ids",
            before.keys,
            after.keys
        )
        for ((id, beforeRow) in before) {
            assertEquals(
                "Read sequence changed a field of transaction $id",
                beforeRow,
                after[id]
            )
        }
    }

    /** Removes all transactions so the next case starts clean. */
    private fun clearStore() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM transactions")
        db.openHelper.writableDatabase.execSQL("DELETE FROM wallets")
        db.openHelper.writableDatabase.execSQL("DELETE FROM categories")
    }

    private fun randomType(rng: Random): TransactionType =
        TransactionType.entries.toTypedArray().random(rng)

    /**
     * Builds a randomly activated [FilterSet]: each dimension is independently active or inactive,
     * so the read sequence exercises every filter path. The precise matching semantics are covered
     * by the pure-logic property tests (Properties 1–3); here the filter is only a *read* operation
     * whose only relevant effect is that it must not mutate the store.
     */
    private fun randomFilterSet(
        rng: Random,
        walletIds: List<Long>,
        categoryIds: List<Long>
    ): FilterSet {
        val types = if (rng.nextBoolean()) {
            TransactionType.entries.filter { rng.nextBoolean() }.toSet()
        } else {
            emptySet()
        }
        val wallets = if (rng.nextBoolean()) {
            walletIds.filter { rng.nextBoolean() }.toSet()
        } else {
            emptySet()
        }
        val categories = if (rng.nextBoolean()) {
            categoryIds.filter { rng.nextBoolean() }.toSet()
        } else {
            emptySet()
        }
        val syncStatuses = if (rng.nextBoolean()) {
            SyncStatus.entries.filter { rng.nextBoolean() }.toSet()
        } else {
            emptySet()
        }
        val timeFilter = if (rng.nextBoolean()) {
            // Include occasionally inverted ranges to exercise the degenerate path.
            TimeFilter(
                startInclusive = rng.nextLong(0, 10_000_000),
                endInclusive = rng.nextLong(0, 10_000_000)
            )
        } else {
            null
        }
        return FilterSet(
            types = types,
            walletIds = wallets,
            categoryIds = categories,
            syncStatuses = syncStatuses,
            timeFilter = timeFilter
        )
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
