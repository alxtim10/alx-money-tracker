package com.alx.moneytracker.ui.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.toEntity
import com.alx.moneytracker.data.repository.RoomHistoryRepository
import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end Room integration test for the **wired** read stack (Task 13.2): a real
 * [RoomHistoryRepository] over an in-memory [AppDatabase], driving a real
 * [TransactionHistoryViewModel], with `uiState` actively collected.
 *
 * This closes the loop that the lower-level tests exercise in isolation:
 * - [com.alx.moneytracker.data.local.TransactionDaoObserveAllTest] proves the DAO `Flow` re-emits
 *   on insert/change/remove (Requirement 2 at the DAO boundary).
 * - [com.alx.moneytracker.data.repository.RoomHistoryRepositoryReadIsolationTest] proves the
 *   repository + pure logic never mutate the store (Property 9).
 *
 * Here the assertions are made against the ViewModel's exposed [TransactionHistoryViewModel.uiState]
 * so the whole chain — Room `Flow` -> repository `Result` mapping -> `combine` -> filter/sort/
 * aggregate/row-map -> `StateFlow` — is verified together.
 *
 * Covered acceptance criteria:
 * - 2.1 / 2.2 — on subscribe, `uiState` reflects the current (seeded) rows and summary totals.
 * - 2.3 / 10.5 / 10.6 — after a Scope 1 insert the rows re-emit and the summary recomputes over the
 *   resulting Filtered_Set.
 * - 2.4 — an `is_synced` change re-emits the list reflecting the change.
 * - 2.5 — a removal re-emits the list excluding the removed transaction.
 * - 12.1 / 12.2 / 12.3 — observing/filtering/sorting/aggregating leaves the `transactions` table
 *   row count and every field value unchanged.
 *
 * ### Why instrumented
 * The behavior under test is stated over the *real* `Transactions_Store` (the Room `transactions`
 * table) and its reactive re-emission, so it runs against an actual Room database built with
 * [Room.inMemoryDatabaseBuilder] + [androidx.room.RoomDatabase.Builder.allowMainThreadQueries],
 * following the conventions of the existing Scope 1/Scope 2 Room tests.
 *
 * ### Driving the ViewModel's StateFlow under instrumentation
 * `uiState` uses `stateIn(..., WhileSubscribed(5000))` + `flowOn(Dispatchers.IO)`, so it only
 * recomputes past [HistoryUiState.Initial] while a collector is active, and its emissions are
 * asynchronous relative to the test thread. Each test therefore launches a background collector on
 * a dedicated [CoroutineScope] and suspends until the expected state arrives via [awaitState]
 * (bounded by a real-time [withTimeout]), rather than reading `uiState.value` synchronously. Real
 * time (`runBlocking`) is used deliberately so the timeout does not fast-forward past the real
 * background work on `Dispatchers.IO`.
 */
@RunWith(AndroidJUnit4::class)
class TransactionHistoryEndToEndTest {

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

    // region seeding helpers (drive writes through Scope 1's atomic write path)

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
     * Persists [tx] through Scope 1's atomic write path
     * ([TransactionDao.insertAndApplyBalances]) with the correct signed balance deltas for its
     * type — the same boundary the Transaction Input Engine uses.
     */
    private suspend fun insertViaScope1WritePath(tx: Transaction) {
        val (sourceDelta, destDelta) = when (tx.type) {
            TransactionType.EXPENSE -> -tx.amount to 0L
            TransactionType.INCOME -> tx.amount to 0L
            TransactionType.TRANSFER -> -tx.amount to tx.amount
        }
        transactionDao.insertAndApplyBalances(
            tx = tx.toEntity(),
            sourceWalletId = tx.sourceWalletId,
            sourceDelta = sourceDelta,
            destWalletId = tx.destWalletId,
            destDelta = destDelta
        )
    }

    private fun expense(id: String, amount: Long, wallet: Long, category: Long, timestamp: Long, isSynced: Boolean = false) =
        Transaction(
            id = id, timestamp = timestamp, type = TransactionType.EXPENSE, amount = amount,
            sourceWalletId = wallet, destWalletId = null, categoryId = category, note = "", isSynced = isSynced
        )

    private fun income(id: String, amount: Long, wallet: Long, category: Long, timestamp: Long, isSynced: Boolean = false) =
        Transaction(
            id = id, timestamp = timestamp, type = TransactionType.INCOME, amount = amount,
            sourceWalletId = wallet, destWalletId = null, categoryId = category, note = "", isSynced = isSynced
        )

    // endregion

    // region store snapshot (raw SQL, bypassing the code under test)

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

    /** Reads the whole `transactions` table directly into an id-keyed snapshot (raw SQL). */
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

    // endregion

    /**
     * 2.1 / 2.2 / 10.5 / 10.6 — On subscribe the wired `uiState` reflects the seeded rows and the
     * summary totals; 2.3 / 10.5 / 10.6 — after a Scope 1 insert the rows re-emit and the summary
     * recomputes over the resulting Filtered_Set.
     *
     * The store is seeded with one INCOME (300) and one EXPENSE (100). The initial emission carries
     * both rows and totals income=300 / expense=100 / net=200. A second EXPENSE (50) is then
     * inserted via Scope 1's write path; the ViewModel re-emits three rows with the summary
     * recomputed to income=300 / expense=150 / net=150.
     */
    @Test
    fun uiState_reflectsSeededRowsAndSummary_andRecomputesOnScope1Insert() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)
        val category = insertCategory(name = "General", type = TransactionType.EXPENSE)
        insertViaScope1WritePath(income("tx-inc", amount = 300L, wallet = wallet, category = category, timestamp = 2_000L))
        insertViaScope1WritePath(expense("tx-exp", amount = 100L, wallet = wallet, category = category, timestamp = 1_000L))

        val viewModel = TransactionHistoryViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val collector = scope.launch { viewModel.uiState.collect { } }
        try {
            // Initial emission: both seeded rows, no filter active (empty FilterSet -> all rows).
            val initial = viewModel.uiState.awaitState { it.rows.size == 2 }
            assertEquals(setOf("tx-inc", "tx-exp"), initial.rows.map { it.id }.toSet())
            assertEquals(300L, initial.totals.incomeTotal)
            assertEquals(100L, initial.totals.expenseTotal)
            assertEquals(200L, initial.totals.netTotal)
            assertFalse(initial.isError)

            // Scope 1 writes another expense; the reactive stream re-emits and totals recompute.
            insertViaScope1WritePath(expense("tx-exp2", amount = 50L, wallet = wallet, category = category, timestamp = 3_000L))

            val afterInsert = viewModel.uiState.awaitState { it.rows.size == 3 }
            assertEquals(setOf("tx-inc", "tx-exp", "tx-exp2"), afterInsert.rows.map { it.id }.toSet())
            assertEquals(300L, afterInsert.totals.incomeTotal)
            assertEquals(150L, afterInsert.totals.expenseTotal)
            assertEquals(150L, afterInsert.totals.netTotal)
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }

    /**
     * 2.4 — When a displayed transaction changes in the store (its `is_synced` flag flips 0 -> 1),
     * the wired `uiState` re-emits reflecting the change.
     *
     * The row's derived sync status is asserted through the ViewModel's rendered row so the whole
     * chain (Room change -> repository -> row mapping -> StateFlow) is exercised.
     */
    @Test
    fun uiState_reEmitsOnTransactionChange() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)
        val category = insertCategory(name = "General", type = TransactionType.INCOME)
        insertViaScope1WritePath(income("tx-change", amount = 200L, wallet = wallet, category = category, timestamp = 1_000L, isSynced = false))

        val viewModel = TransactionHistoryViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val collector = scope.launch { viewModel.uiState.collect { } }
        try {
            val before = viewModel.uiState.awaitState { it.rows.size == 1 }
            assertEquals(com.alx.moneytracker.domain.SyncStatus.PENDING, before.rows.single().syncStatus)

            // A later synchronization scope flips is_synced to 1 (a change to an existing row).
            db.openHelper.writableDatabase.execSQL(
                "UPDATE transactions SET is_synced = 1 WHERE id = ?",
                arrayOf<Any>("tx-change")
            )

            val after = viewModel.uiState.awaitState {
                it.rows.singleOrNull()?.syncStatus == com.alx.moneytracker.domain.SyncStatus.SYNCED
            }
            assertEquals(1, after.rows.size)
            assertEquals("tx-change", after.rows.single().id)
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }

    /**
     * 2.5 — When a transaction is removed from the store, the wired `uiState` re-emits a list that
     * excludes the removed transaction and recomputes the summary over the remaining Filtered_Set.
     */
    @Test
    fun uiState_reEmitsExcludingRemovedTransaction() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)
        val category = insertCategory(name = "General", type = TransactionType.EXPENSE)
        insertViaScope1WritePath(expense("tx-keep", amount = 100L, wallet = wallet, category = category, timestamp = 1_000L))
        insertViaScope1WritePath(expense("tx-remove", amount = 400L, wallet = wallet, category = category, timestamp = 2_000L))

        val viewModel = TransactionHistoryViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val collector = scope.launch { viewModel.uiState.collect { } }
        try {
            val before = viewModel.uiState.awaitState { it.rows.size == 2 }
            assertEquals(setOf("tx-keep", "tx-remove"), before.rows.map { it.id }.toSet())
            assertEquals(500L, before.totals.expenseTotal)

            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM transactions WHERE id = ?",
                arrayOf<Any>("tx-remove")
            )

            val after = viewModel.uiState.awaitState { it.rows.size == 1 }
            assertEquals("tx-keep", after.rows.single().id)
            assertFalse("Removed transaction must not remain in the rows", after.rows.any { it.id == "tx-remove" })
            // Summary recomputed over the remaining Filtered_Set (Requirement 10.6).
            assertEquals(100L, after.totals.expenseTotal)
        } finally {
            collector.cancel()
            scope.cancel()
        }
    }

    /**
     * 12.1 / 12.2 / 12.3 — Observing through the wired ViewModel and exercising every read
     * dimension (filter toggles, all four sort orders, summary aggregation) leaves the
     * `transactions` table row count and every field value unchanged.
     *
     * The store is snapshotted (raw SQL, bypassing the code under test) before and after a sequence
     * of read/filter/sort/aggregate operations driven entirely through the ViewModel's public
     * surface, then compared field-by-field.
     */
    @Test
    fun observingFilteringSortingAggregating_leavesStoreUnchanged() = runBlocking {
        val walletA = insertWallet(name = "Cash", balance = 1_000_000L)
        val walletB = insertWallet(name = "Bank", balance = 1_000_000L)
        val catExpense = insertCategory(name = "Food", type = TransactionType.EXPENSE)
        val catIncome = insertCategory(name = "Salary", type = TransactionType.INCOME)

        insertViaScope1WritePath(expense("tx-1", amount = 100L, wallet = walletA, category = catExpense, timestamp = 1_000L, isSynced = false))
        insertViaScope1WritePath(income("tx-2", amount = 300L, wallet = walletB, category = catIncome, timestamp = 2_000L, isSynced = true))
        insertViaScope1WritePath(expense("tx-3", amount = 100L, wallet = walletB, category = catExpense, timestamp = 3_000L, isSynced = false))
        insertViaScope1WritePath(
            Transaction(
                id = "tx-4", timestamp = 4_000L, type = TransactionType.TRANSFER, amount = 250L,
                sourceWalletId = walletA, destWalletId = walletB, categoryId = catExpense, note = "", isSynced = false
            )
        )

        val before = snapshotStore()

        val viewModel = TransactionHistoryViewModel(repository)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val collector = scope.launch { viewModel.uiState.collect { } }
        try {
            // Read the initial state.
            viewModel.uiState.awaitState { it.rows.size == 4 }

            // Filter by type (activate then combine with a wallet dimension).
            viewModel.onEvent(HistoryEvent.TypeChipToggled(TransactionType.EXPENSE))
            viewModel.uiState.awaitState { it.filterSet.types.contains(TransactionType.EXPENSE) }
            viewModel.onEvent(HistoryEvent.WalletChipToggled(walletB))
            viewModel.uiState.awaitState { it.filterSet.walletIds.contains(walletB) }

            // Exercise all four sort orders (each re-runs sort + aggregate against the store data).
            for (order in SortOrder.entries) {
                viewModel.onEvent(HistoryEvent.SortSelected(order))
                viewModel.uiState.awaitState { it.sortOrder == order }
            }

            // Clear the filters back to the full set to exercise the empty-FilterSet path too.
            viewModel.onEvent(HistoryEvent.TypeChipToggled(TransactionType.EXPENSE))
            viewModel.onEvent(HistoryEvent.WalletChipToggled(walletB))
            viewModel.uiState.awaitState { it.filterSet.isEmpty && it.rows.size == 4 }
        } finally {
            collector.cancel()
            scope.cancel()
        }

        val after = snapshotStore()

        assertEquals("Read sequence changed the transaction row count", before.size, after.size)
        assertEquals("Read sequence changed the set of transaction ids", before.keys, after.keys)
        assertTrue("Expected four seeded transactions to remain", after.size == 4)
        for ((id, beforeRow) in before) {
            assertEquals("Read sequence changed a field of transaction $id", beforeRow, after[id])
        }
    }
}

/**
 * Suspends until [this] emits a [HistoryUiState] satisfying [predicate], returning that state.
 *
 * Bounded by a real-time [withTimeout] so a never-satisfied predicate fails fast instead of
 * hanging. Mirrors the `awaitState` helper in the ViewModel unit test, but under instrumentation:
 * the ViewModel's pipeline runs on a real background dispatcher (`flowOn(Dispatchers.IO)`) with
 * `WhileSubscribed`, so callers must keep a collector active and await the expected state rather
 * than reading `value` synchronously.
 */
private suspend fun StateFlow<HistoryUiState>.awaitState(
    predicate: (HistoryUiState) -> Boolean
): HistoryUiState = withTimeout(5_000) { first(predicate) }
