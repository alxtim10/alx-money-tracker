package com.alx.moneytracker.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.entity.TransactionEntity
import com.alx.moneytracker.domain.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room in-memory integration tests for the Scope 3 wallet-management DAO methods added to
 * [WalletDao] (Task 5.2).
 *
 * These are instrumented tests: they build a real Room [AppDatabase] with
 * [Room.inMemoryDatabaseBuilder] and [androidx.room.RoomDatabase.Builder.allowMainThreadQueries]
 * so suspend DAO calls run under [runBlocking] on the test thread, exercising the actual queries
 * and `@Transaction` boundaries rather than fakes.
 *
 * Two distinct concerns are covered:
 *
 * ### 1. Direct Balance Override vs. Scope 1's transactional write (AGENTS.md Data Rule 3)
 * A [WalletDao.overrideBalance] performs a single `UPDATE wallets SET balance = :value` and inserts
 * **no** `transactions` row (Requirements 4.3, 4.4). This is contrasted directly against Scope 1's
 * [TransactionDao.insertAndApplyBalances], which — for the same net balance effect — *does* insert a
 * `transactions` row. The two write paths are provably distinct in their effect on the
 * `transactions` table.
 *
 * ### 2. Atomic default assignment / archive-reassignment (AGENTS.md Data Rule 1)
 * [WalletDao.assignDefault] and [WalletDao.archiveAndReassignDefault] each run inside one
 * `@Transaction`, so the exactly-one-default invariant never has an observable gap (Requirements
 * 6.1, 6.2, 5.5). An induced mid-`@Transaction` failure rolls back all changes, so the prior
 * default designation is retained (Requirement 6.5).
 *
 * ### Induced-failure mechanism (for the 6.5 rollback test)
 * [WalletDao.assignDefault] runs `clearAllDefaults()` first and then `setDefault(id)`. To force a
 * failure *after* `clearAllDefaults()` has already run — so a naive non-atomic implementation would
 * be left with zero defaults — this test installs a SQLite trigger that ABORTs the `setDefault`
 * UPDATE (an update that sets `is_default = 1`) for a sentinel wallet. Because both steps share one
 * `@Transaction`, Room rolls back the `clearAllDefaults()` too, retaining the prior default.
 */
@RunWith(AndroidJUnit4::class)
class WalletDaoManagementTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var transactionDao: TransactionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        transactionDao = db.transactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Inserts a wallet directly via raw SQL and returns its generated row id. */
    private fun insertWallet(
        name: String,
        balance: Long,
        isDefault: Boolean = false,
        isArchived: Boolean = false
    ): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO wallets (name, balance, is_default, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, balance, if (isDefault) 1 else 0, if (isArchived) 1 else 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Reads a single wallet's balance directly, or null if the row is absent. */
    private fun readBalance(walletId: Long): Long? {
        db.openHelper.writableDatabase
            .query("SELECT balance FROM wallets WHERE id = ?", arrayOf<Any>(walletId))
            .use { cursor -> return if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    /** Counts rows in the transactions table. */
    private fun transactionCount(): Long {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Returns the set of wallet ids currently flagged `is_default = 1` among non-archived rows. */
    private fun activeDefaultIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        db.openHelper.writableDatabase
            .query("SELECT id FROM wallets WHERE is_default = 1 AND is_archived = 0")
            .use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
        return ids
    }

    /** Returns the set of all wallet ids flagged `is_default = 1` (regardless of archive state). */
    private fun allDefaultIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        db.openHelper.writableDatabase
            .query("SELECT id FROM wallets WHERE is_default = 1")
            .use { cursor ->
                while (cursor.moveToNext()) ids.add(cursor.getLong(0))
            }
        return ids
    }

    private fun sampleTransaction(
        id: String,
        type: TransactionType,
        amount: Long,
        sourceWalletId: Long,
        destWalletId: Long? = null,
        categoryId: Long = 1L
    ) = TransactionEntity(
        id = id,
        timestamp = 1_000L,
        type = type.name,
        amount = amount,
        sourceWalletId = sourceWalletId,
        destWalletId = destWalletId,
        categoryId = categoryId,
        note = "",
        isSynced = 0
    )

    // ---------------------------------------------------------------------------------------------
    // Override vs. Scope 1 contrast (Requirements 4.3, 4.4)
    // ---------------------------------------------------------------------------------------------

    /**
     * 4.1 / 4.3 / 4.4 — [WalletDao.overrideBalance] overwrites the balance with a single `wallets`
     * UPDATE and inserts **no** `transactions` row. The transactions row count is unchanged.
     */
    @Test
    fun overrideBalance_updatesWalletWithNoTransactionInsert() = runBlocking {
        val walletId = insertWallet(name = "Cash", balance = 1_000L, isDefault = true)
        val startingCount = transactionCount()

        walletDao.overrideBalance(id = walletId, balance = 250L)

        // The balance was overwritten (an absolute set, not a delta).
        assertEquals(250L, readBalance(walletId))
        // No transactions row was created — the count is exactly as before (Req 4.3, 4.4).
        assertEquals(startingCount, transactionCount())
    }

    /**
     * 4.3 / 4.4 (contrast) — Scope 1's [TransactionDao.insertAndApplyBalances] and Scope 3's
     * [WalletDao.overrideBalance] are provably distinct in their effect on the `transactions`
     * table: the transactional write inserts a row while the override does not, even when both
     * drive the wallet balance to the same final value.
     */
    @Test
    fun overridePath_and_scope1Path_areDistinctInTransactionEffect() = runBlocking {
        // Scope 1 path: an EXPENSE of 300 against 1000 lands the balance at 700 AND inserts a row.
        val scope1Wallet = insertWallet(name = "Scope1", balance = 1_000L)
        val countBeforeScope1 = transactionCount()
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-1", TransactionType.EXPENSE, 300L, scope1Wallet),
            sourceWalletId = scope1Wallet,
            sourceDelta = -300L,
            destWalletId = null,
            destDelta = 0L
        )
        assertEquals(700L, readBalance(scope1Wallet))
        // Scope 1 inserts exactly one transactions row.
        assertEquals(countBeforeScope1 + 1, transactionCount())

        // Scope 3 path: drive a different wallet to the same 700 via an override — no row inserted.
        val overrideWallet = insertWallet(name = "Override", balance = 1_000L)
        val countBeforeOverride = transactionCount()
        walletDao.overrideBalance(id = overrideWallet, balance = 700L)
        assertEquals(700L, readBalance(overrideWallet))
        // The override adds no transactions row: the count is unchanged (Req 4.3, 4.4).
        assertEquals(countBeforeOverride, transactionCount())
    }

    // ---------------------------------------------------------------------------------------------
    // Atomic default assignment (Requirements 6.1, 6.2)
    // ---------------------------------------------------------------------------------------------

    /**
     * 6.1 / 6.2 — [WalletDao.assignDefault] sets exactly the target wallet's `is_default` to true
     * and clears every other wallet's flag, leaving exactly one default.
     */
    @Test
    fun assignDefault_leavesExactlyOneDefault() = runBlocking {
        val a = insertWallet(name = "Alpha", balance = 100L, isDefault = true)
        val b = insertWallet(name = "Bravo", balance = 200L)
        val c = insertWallet(name = "Charlie", balance = 300L)

        walletDao.assignDefault(b)

        // Exactly one wallet is the default, and it is the target.
        assertEquals(setOf(b), allDefaultIds())
        assertEquals(setOf(b), activeDefaultIds())
        // The previously-default wallet was cleared.
        assertTrue("Prior default must be cleared", a !in allDefaultIds())
        assertTrue("Untouched wallet must not become default", c !in allDefaultIds())
    }

    /**
     * 6.5 — When the atomic default assignment fails mid-`@Transaction` (after `clearAllDefaults()`
     * has run), Room rolls back all changes so the prior default designation is retained.
     *
     * See the class comment for the induced-failure mechanism: a trigger ABORTs the `setDefault`
     * UPDATE for a sentinel wallet, forcing a failure *after* `clearAllDefaults()`.
     */
    @Test
    fun assignDefault_failingMidTransaction_retainsPriorDefault() = runBlocking {
        val priorDefault = insertWallet(name = "Alpha", balance = 100L, isDefault = true)
        // A sentinel wallet whose is_default UPDATE will be aborted by the trigger below.
        val poisoned = insertWallet(name = "Poison", balance = 200L)

        // Trigger ABORTs any UPDATE that sets is_default = 1 for the sentinel wallet. Because
        // assignDefault runs clearAllDefaults() first, this fails *after* defaults are cleared.
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER guard_block_poison_default BEFORE UPDATE ON wallets
            WHEN NEW.is_default = 1 AND NEW.id = $poisoned
            BEGIN
                SELECT RAISE(ABORT, 'default assignment blocked for test');
            END;
            """.trimIndent()
        )

        val defaultsBefore = allDefaultIds()
        assertEquals(setOf(priorDefault), defaultsBefore)

        var threw = false
        try {
            walletDao.assignDefault(poisoned)
        } catch (e: Exception) {
            threw = true
        }

        assertTrue("The blocked default assignment should have thrown", threw)
        // Rollback: the prior default is retained; clearAllDefaults() was rolled back too (Req 6.5).
        assertEquals(setOf(priorDefault), allDefaultIds())
    }

    // ---------------------------------------------------------------------------------------------
    // Atomic archive + deterministic default reassignment (Requirements 5.5, 6.1, 6.2)
    // ---------------------------------------------------------------------------------------------

    /**
     * 5.5 / 6.1 / 6.2 — Archiving the current default while a replacement is supplied archives the
     * target and reassigns the default to the replacement, leaving exactly one *active* default.
     */
    @Test
    fun archiveAndReassignDefault_reassignsToReplacement_exactlyOneActiveDefault() = runBlocking {
        val current = insertWallet(name = "Alpha", balance = 100L, isDefault = true)
        val replacement = insertWallet(name = "Bravo", balance = 200L)
        val other = insertWallet(name = "Charlie", balance = 300L)

        walletDao.archiveAndReassignDefault(id = current, replacementId = replacement)

        // The archived wallet is no longer an active default.
        assertTrue("Archived wallet must not be an active default", current !in activeDefaultIds())
        // Exactly one active default remains, and it is the replacement.
        assertEquals(setOf(replacement), activeDefaultIds())
        assertTrue("Untouched wallet must not become default", other !in allDefaultIds())
    }

    /**
     * 5.6 — Archiving the last active wallet with no replacement leaves no wallet designated as the
     * default (a null replacement means "no reassignment").
     */
    @Test
    fun archiveAndReassignDefault_noReplacement_leavesNoActiveDefault() = runBlocking {
        val only = insertWallet(name = "Alpha", balance = 100L, isDefault = true)

        walletDao.archiveAndReassignDefault(id = only, replacementId = null)

        assertTrue("No active default should remain", activeDefaultIds().isEmpty())
    }

    /**
     * 6.5 (archive path) — When the reassignment step fails mid-`@Transaction`, Room rolls back the
     * archive as well, so the prior default designation and archive state are both retained.
     */
    @Test
    fun archiveAndReassignDefault_failingMidTransaction_retainsPriorState() = runBlocking {
        val current = insertWallet(name = "Alpha", balance = 100L, isDefault = true)
        val poisoned = insertWallet(name = "Poison", balance = 200L)

        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER guard_block_poison_default BEFORE UPDATE ON wallets
            WHEN NEW.is_default = 1 AND NEW.id = $poisoned
            BEGIN
                SELECT RAISE(ABORT, 'default reassignment blocked for test');
            END;
            """.trimIndent()
        )

        var threw = false
        try {
            walletDao.archiveAndReassignDefault(id = current, replacementId = poisoned)
        } catch (e: Exception) {
            threw = true
        }

        assertTrue("The blocked reassignment should have thrown", threw)
        // Rollback: the prior default is retained and the target was not archived (Req 6.5).
        assertEquals(setOf(current), allDefaultIds())
        assertEquals(setOf(current), activeDefaultIds())
        assertNull(
            "The target must not have been archived (rolled back)",
            readArchivedFlagIfActive(current)
        )
    }

    /**
     * Returns null when the wallet is still active (`is_archived = 0`), or a non-null marker when it
     * has been archived. Used to assert the archive step was rolled back.
     */
    private fun readArchivedFlagIfActive(walletId: Long): Int? {
        db.openHelper.writableDatabase
            .query("SELECT is_archived FROM wallets WHERE id = ?", arrayOf<Any>(walletId))
            .use { cursor ->
                if (!cursor.moveToFirst()) return 1 // absent counts as "not active"
                val archived = cursor.getInt(0)
                return if (archived == 0) null else archived
            }
    }
}
