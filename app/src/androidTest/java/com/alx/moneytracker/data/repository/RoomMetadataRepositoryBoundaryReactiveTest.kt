package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.toEntity
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room in-memory **integration** tests for Task 14.2: the transaction-untouched boundary and the
 * reactive `Flow` re-emission contract of the wired [RoomMetadataRepository].
 *
 * These are example-based integration tests (not property tests) per the design's Testing Strategy:
 * the transaction-integrity *invariant* is property-tested in
 * [RoomMetadataRepositoryTransactionIntegrityTest], while reactive `Flow` re-emission and the
 * real-DB boundary "headline" check do not vary meaningfully with input and are covered here by a
 * few well-chosen examples.
 *
 * ### Why this is an instrumented test
 * The behavior under test spans the *real* Room database — the `transactions` table and the three
 * metadata tables — so it runs against an actual [AppDatabase] built with
 * [Room.inMemoryDatabaseBuilder] + [androidx.room.RoomDatabase.Builder.allowMainThreadQueries],
 * matching the conventions of the sibling Scope 1 & 2 Room tests
 * (`TransactionDaoAtomicityTest`, `RoomHistoryRepositoryReadIsolationTest`,
 * `RoomMetadataRepositoryTransactionIntegrityTest`). The repository is the same
 * [RoomMetadataRepository] the app wires over the metadata DAOs, so the structural boundary (no
 * `TransactionDao` dependency) is exercised end to end. Seed transactions are inserted through
 * Scope 1's write path ([TransactionDao.insertAndApplyBalances]).
 *
 * ### What each test asserts
 * - [directBalanceOverride_leavesTheTransactionsStoreUntouched],
 *   [archivingAWallet_leavesTheTransactionsStoreUntouched], and the full-CRUD-cycle tests: seed the
 *   `transactions` table, snapshot every row/field via raw SQL (bypassing the code under test),
 *   run the operation through the repository, and assert the row count and every field value are
 *   unchanged (Data Rule 3 / Requirement 15: 15.1, 15.2, 15.3).
 * - The re-emission tests collect the active `Flow` after the corresponding change and assert the
 *   new emission reflects the updated active set (Requirements 2.4, 8.4, 12.4).
 * - The archive-visibility tests assert an archived wallet/category is absent from the active
 *   `Flow` yet still present in its table, queried directly via raw SQL (Requirements 5.2, 5.3,
 *   10.2, 10.3).
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryBoundaryReactiveTest {

    private lateinit var db: AppDatabase
    private lateinit var transactionDao: TransactionDao
    private lateinit var repository: RoomMetadataRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transactionDao = db.transactionDao()
        repository = RoomMetadataRepository(
            walletDao = db.walletDao(),
            categoryDao = db.categoryDao(),
            quickPresetDao = db.quickPresetDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // region Transaction-untouched boundary (Data Rule 3 / Requirement 15)

    /**
     * A `Direct_Balance_Override` on a wallet overwrites only that wallet's balance and adds no
     * `transactions` row: the seeded store is byte-for-byte unchanged (Requirements 15.1, 15.3).
     */
    @Test
    fun directBalanceOverride_leavesTheTransactionsStoreUntouched() = runBlocking {
        val (walletIds, categoryId) = seedMetadata()
        seedTransactions(walletIds, categoryId)
        val before = snapshotTransactions()

        val result = repository.overrideBalance(walletIds.first(), target = 42_000L)
        assertTrue("override should succeed", result.isSuccess)

        assertTransactionsUnchanged("Direct_Balance_Override", before)
        // The override did take effect on the wallet itself (sanity: the op ran, not a no-op).
        assertEquals(42_000L, db.walletDao().findById(walletIds.first())!!.balance)
    }

    /**
     * Archiving a wallet is a soft delete confined to the `wallets` table: it never touches the
     * seeded `transactions` store (Requirements 15.1, 15.2, 15.3).
     */
    @Test
    fun archivingAWallet_leavesTheTransactionsStoreUntouched() = runBlocking {
        val (walletIds, categoryId) = seedMetadata()
        seedTransactions(walletIds, categoryId)
        val before = snapshotTransactions()

        // Archive a non-default wallet so no default reassignment is needed.
        val result = repository.archiveWallet(walletIds.last())
        assertTrue("archive should succeed", result.isSuccess)

        assertTransactionsUnchanged("archive wallet", before)
    }

    /**
     * A full create -> update -> archive cycle on a Wallet leaves the seeded `transactions` store
     * unchanged after each step (the headline Data Rule 3 / Requirement 15 check for wallets).
     */
    @Test
    fun fullWalletCrudCycle_leavesTheTransactionsStoreUntouched() = runBlocking {
        val (walletIds, categoryId) = seedMetadata()
        seedTransactions(walletIds, categoryId)
        val before = snapshotTransactions()

        // Create
        assertTrue(repository.createWallet(NewWallet(name = "New Kantong", balance = 5_000L)).isSuccess)
        assertTransactionsUnchanged("create wallet", before)

        val created = repository.observeWallets().first().first { it.name == "New Kantong" }

        // Update (rename + override)
        assertTrue(repository.renameWallet(created.id, "Renamed Kantong").isSuccess)
        assertTransactionsUnchanged("rename wallet", before)
        assertTrue(repository.overrideBalance(created.id, target = 9_000L).isSuccess)
        assertTransactionsUnchanged("override balance", before)

        // Archive
        assertTrue(repository.archiveWallet(created.id).isSuccess)
        assertTransactionsUnchanged("archive wallet", before)
    }

    /**
     * A full create -> update -> archive cycle on a Category leaves the seeded `transactions` store
     * unchanged after each step (Requirements 15.1, 15.2, 15.3).
     */
    @Test
    fun fullCategoryCrudCycle_leavesTheTransactionsStoreUntouched() = runBlocking {
        val (walletIds, categoryId) = seedMetadata()
        seedTransactions(walletIds, categoryId)
        val before = snapshotTransactions()

        // Create
        assertTrue(
            repository.createCategory(
                CategoryFields(name = "Bonus", type = TransactionType.INCOME, icon = "gift")
            ).isSuccess
        )
        assertTransactionsUnchanged("create category", before)

        val created = repository.observeCategories().first().first { it.name == "Bonus" }

        // Update
        assertTrue(
            repository.updateCategory(created.id, CategoryPatch(name = "Bonus Tahunan")).isSuccess
        )
        assertTransactionsUnchanged("update category", before)

        // Archive
        assertTrue(repository.archiveCategory(created.id).isSuccess)
        assertTransactionsUnchanged("archive category", before)
    }

    /**
     * A full create -> update -> delete cycle on a Quick_Preset leaves the seeded `transactions`
     * store unchanged after each step. Presets are hard-deleted, but the deletion is confined to
     * the `quick_presets` table (Requirements 15.1, 15.2, 15.3).
     */
    @Test
    fun fullPresetCrudCycle_leavesTheTransactionsStoreUntouched() = runBlocking {
        val (walletIds, categoryId) = seedMetadata()
        seedTransactions(walletIds, categoryId)
        val before = snapshotTransactions()

        // Create
        assertTrue(repository.createPreset(PresetFields(amount = 10_000L, label = "+10k")).isSuccess)
        assertTransactionsUnchanged("create preset", before)

        val created = repository.observeQuickPresets().first().first { it.label == "+10k" }

        // Update
        assertTrue(repository.updatePreset(created.id, PresetPatch(amount = 20_000L)).isSuccess)
        assertTransactionsUnchanged("update preset", before)

        // Delete (hard)
        assertTrue(repository.deletePreset(created.id).isSuccess)
        assertTransactionsUnchanged("delete preset", before)
    }

    // endregion

    // region Reactive Flow re-emission (Data Rule 2 / Requirements 2.4, 8.4, 12.4)

    /**
     * `observeWallets()` re-emits the updated active set after a create, an update, an archive, and
     * an override (Requirement 2.4). Each assertion collects the current emission *after* the write
     * and checks the active set reflects the change.
     */
    @Test
    fun observeWallets_reEmitsUpdatedActiveSetAfterEachChange() = runBlocking {
        val (walletIds, _) = seedMetadata()

        // Baseline: the seeded active wallets are present.
        assertEquals(walletIds.size, repository.observeWallets().first().size)

        // Create -> new active wallet appears.
        assertTrue(repository.createWallet(NewWallet(name = "Tabungan", balance = 0L)).isSuccess)
        val afterCreate = repository.observeWallets().first()
        assertEquals(walletIds.size + 1, afterCreate.size)
        val created = afterCreate.first { it.name == "Tabungan" }

        // Update (rename) -> the emitted name reflects the change.
        assertTrue(repository.renameWallet(created.id, "Tabungan Utama").isSuccess)
        assertEquals(
            "Tabungan Utama",
            repository.observeWallets().first().first { it.id == created.id }.name
        )

        // Override -> the emitted balance reflects the change.
        assertTrue(repository.overrideBalance(created.id, target = 123_000L).isSuccess)
        assertEquals(
            123_000L,
            repository.observeWallets().first().first { it.id == created.id }.balance
        )

        // Archive -> the wallet drops out of the active emission.
        assertTrue(repository.archiveWallet(created.id).isSuccess)
        val afterArchive = repository.observeWallets().first()
        assertFalse(afterArchive.any { it.id == created.id })
        assertEquals(walletIds.size, afterArchive.size)
    }

    /**
     * `observeCategories()` re-emits the updated active set after a create, an update, and an
     * archive (Requirement 8.4).
     */
    @Test
    fun observeCategories_reEmitsUpdatedActiveSetAfterEachChange() = runBlocking {
        val (_, categoryId) = seedMetadata()

        val baselineSize = repository.observeCategories().first().size

        // Create.
        assertTrue(
            repository.createCategory(
                CategoryFields(name = "Transport", type = TransactionType.EXPENSE, icon = null)
            ).isSuccess
        )
        val afterCreate = repository.observeCategories().first()
        assertEquals(baselineSize + 1, afterCreate.size)
        val created = afterCreate.first { it.name == "Transport" }

        // Update.
        assertTrue(repository.updateCategory(created.id, CategoryPatch(name = "Transportasi")).isSuccess)
        assertEquals(
            "Transportasi",
            repository.observeCategories().first().first { it.id == created.id }.name
        )

        // Archive the originally-seeded category -> it drops out of the active emission.
        assertTrue(repository.archiveCategory(categoryId).isSuccess)
        val afterArchive = repository.observeCategories().first()
        assertFalse(afterArchive.any { it.id == categoryId })
    }

    /**
     * `observeQuickPresets()` re-emits the updated set after a create, an update, and a delete
     * (Requirement 12.4).
     */
    @Test
    fun observeQuickPresets_reEmitsUpdatedSetAfterEachChange() = runBlocking {
        // Baseline: no seeded presets.
        assertEquals(0, repository.observeQuickPresets().first().size)

        // Create.
        assertTrue(repository.createPreset(PresetFields(amount = 5_000L, label = "+5k")).isSuccess)
        val afterCreate = repository.observeQuickPresets().first()
        assertEquals(1, afterCreate.size)
        val created = afterCreate.first()

        // Update.
        assertTrue(repository.updatePreset(created.id, PresetPatch(label = "+5rb")).isSuccess)
        assertEquals("+5rb", repository.observeQuickPresets().first().first { it.id == created.id }.label)

        // Delete.
        assertTrue(repository.deletePreset(created.id).isSuccess)
        assertEquals(0, repository.observeQuickPresets().first().size)
    }

    // endregion

    // region Archived record: absent from active Flow, still present in the table

    /**
     * An archived wallet is absent from the active `observeWallets()` `Flow` while its row is still
     * present in the `wallets` table with `is_archived = 1` (Requirements 5.2, 5.3).
     */
    @Test
    fun archivedWallet_isAbsentFromActiveFlowButStillInTable() = runBlocking {
        val (walletIds, _) = seedMetadata()
        val target = walletIds.last()

        assertTrue(repository.archiveWallet(target).isSuccess)

        // Absent from the active Flow.
        assertFalse(repository.observeWallets().first().any { it.id == target })

        // Still present in the table, flagged archived (queried directly, bypassing the repository).
        val row = queryWalletRow(target)
        assertNotNull("archived wallet row should still exist in the table", row)
        assertEquals(1, row!!.second)
    }

    /**
     * An archived category is absent from the active `observeCategories()` `Flow` while its row is
     * still present in the `categories` table with `is_archived = 1` (Requirements 10.2, 10.3).
     */
    @Test
    fun archivedCategory_isAbsentFromActiveFlowButStillInTable() = runBlocking {
        val (_, categoryId) = seedMetadata()

        assertTrue(repository.archiveCategory(categoryId).isSuccess)

        // Absent from the active Flow.
        assertFalse(repository.observeCategories().first().any { it.id == categoryId })

        // Still present in the table, flagged archived.
        val row = queryCategoryRow(categoryId)
        assertNotNull("archived category row should still exist in the table", row)
        assertEquals(1, row!!.second)
    }

    // endregion

    // region Seeding helpers

    /**
     * Seeds a small pool of active wallets (the first is default) and one active category via raw
     * SQL, returning their generated ids. Presets are seeded per-test where needed.
     */
    private fun seedMetadata(): Pair<List<Long>, Long> {
        val walletIds = listOf(
            insertWallet(name = "Cash", balance = 1_000_000L, isDefault = true),
            insertWallet(name = "Bank", balance = 2_000_000L, isDefault = false),
            insertWallet(name = "E-Wallet", balance = 500_000L, isDefault = false)
        )
        val categoryId = insertCategory(name = "Makan", type = TransactionType.EXPENSE)
        return walletIds to categoryId
    }

    /** Seeds a handful of transactions through Scope 1's atomic write path. */
    private fun seedTransactions(walletIds: List<Long>, categoryId: Long) = runBlocking {
        val source = walletIds.first()
        repeat(5) { i ->
            val amount = 10_000L * (i + 1)
            val tx = Transaction(
                id = "seed-tx-$i",
                timestamp = 1_000L + i,
                type = TransactionType.EXPENSE,
                amount = amount,
                sourceWalletId = source,
                destWalletId = null,
                categoryId = categoryId,
                note = if (i % 2 == 0) "note-$i" else "",
                isSynced = i % 2 == 0
            )
            transactionDao.insertAndApplyBalances(
                tx = tx.toEntity(),
                sourceWalletId = source,
                sourceDelta = -amount,
                destWalletId = null,
                destDelta = 0L
            )
        }
    }

    // endregion

    // region Raw-SQL seeding + snapshotting (bypasses the code under test)

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

    private fun insertWallet(name: String, balance: Long, isDefault: Boolean): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO wallets (name, balance, is_default, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, balance, if (isDefault) 1 else 0, 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

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

    /** Returns `(is_archived-as-Int)` pair for a wallet row, or null if absent. Second = is_archived. */
    private fun queryWalletRow(id: Long): Pair<Long, Int>? {
        db.openHelper.writableDatabase.query(
            "SELECT id, is_archived FROM wallets WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0) to cursor.getInt(1)
        }
    }

    /** Returns `(id, is_archived-as-Int)` for a category row, or null if absent. */
    private fun queryCategoryRow(id: Long): Pair<Long, Int>? {
        db.openHelper.writableDatabase.query(
            "SELECT id, is_archived FROM categories WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0) to cursor.getInt(1)
        }
    }

    /**
     * Reads the entire `transactions` table directly (raw SQL, not the repository) into a stable,
     * order-independent snapshot keyed by id.
     */
    private fun snapshotTransactions(): Map<String, TxRow> {
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

    /** Asserts the current `transactions` snapshot matches [before] in count and every field. */
    private fun assertTransactionsUnchanged(op: String, before: Map<String, TxRow>) {
        val after = snapshotTransactions()
        assertEquals("$op changed the transaction row count", before.size, after.size)
        assertEquals("$op changed the set of transaction ids", before.keys, after.keys)
        for ((id, beforeRow) in before) {
            assertEquals("$op changed a field of transaction $id", beforeRow, after[id])
        }
    }

    // endregion
}
