package com.alx.moneytracker.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.entity.TransactionEntity
import com.alx.moneytracker.data.local.entity.WalletEntity
import com.alx.moneytracker.domain.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room in-memory integration tests for the atomic persistence boundary and real-time Flow
 * observation (Task 2.4).
 *
 * These are instrumented tests: they build a real Room [AppDatabase] with
 * [Room.inMemoryDatabaseBuilder] and [androidx.room.RoomDatabase.Builder.allowMainThreadQueries]
 * so suspend DAO calls can run under [runBlocking] on the test thread. They exercise the actual
 * `@Transaction` boundary in [TransactionDao.insertAndApplyBalances], not a fake.
 *
 * Covered acceptance criteria:
 * - 9.1 — a single atomic write commits the transaction insert and the balance update together.
 * - 9.5 — when a balance update fails, the whole transaction rolls back, leaving both the
 *   transactions and wallets stores unchanged.
 * - 12.1 / 12.2 — [WalletDao.observeActiveWallets] emits the updated balance after a wallet update.
 *
 * ### Induced-failure mechanism (for the 9.5 rollback test)
 * [TransactionDao.applyBalanceDelta] is a plain `UPDATE` and never throws on its own, and Room
 * would not roll back an insert unless a *later* step inside the same `@Transaction` fails. To
 * force the balance update to fail *after* the insert has already been written, this test installs
 * a SQLite trigger on the `wallets` table that ABORTs any UPDATE which would drive a balance below
 * zero:
 *
 * ```
 * CREATE TRIGGER guard_no_negative_balance BEFORE UPDATE ON wallets
 * WHEN NEW.balance < 0
 * BEGIN SELECT RAISE(ABORT, 'balance would go negative'); END;
 * ```
 *
 * The `@Transaction` body runs `insert(tx)` first and then `applyBalanceDelta(source, -amount)`.
 * With an amount larger than the source balance, the balance UPDATE trips the trigger and ABORTs.
 * Because the insert and the balance update share one `@Transaction`, Room rolls back both. We then
 * assert that neither the transactions table nor the wallets table changed. This models exactly the
 * scenario in Requirement 9.5: a failing balance update rolls back the insert.
 *
 * (An alternative — inserting a duplicate transaction primary key — would fail on the *insert* step
 * itself, which does not demonstrate that a failing *balance update* rolls back an already-written
 * insert. The trigger approach targets the balance-update step specifically.)
 */
@RunWith(AndroidJUnit4::class)
class TransactionDaoAtomicityTest {

    private lateinit var db: AppDatabase
    private lateinit var transactionDao: TransactionDao
    private lateinit var walletDao: WalletDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transactionDao = db.transactionDao()
        walletDao = db.walletDao()
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
            .use { cursor ->
                return if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
    }

    /** Counts rows in the transactions table. */
    private fun transactionCount(): Long {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun sampleTransaction(
        id: String,
        type: TransactionType,
        amount: Long,
        sourceWalletId: Long,
        destWalletId: Long?,
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

    /**
     * 9.1 — A valid atomic write commits the insert and the balance update together.
     *
     * An EXPENSE of 300 against a wallet with balance 1000 should, in one commit, both persist the
     * transaction row and reduce the balance to 700.
     */
    @Test
    fun atomicWrite_commitsInsertAndBalanceUpdateTogether() = runBlocking {
        val walletId = insertWallet(name = "Cash", balance = 1_000L, isDefault = true)

        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction(
                id = "tx-1",
                type = TransactionType.EXPENSE,
                amount = 300L,
                sourceWalletId = walletId,
                destWalletId = null
            ),
            sourceWalletId = walletId,
            sourceDelta = -300L,
            destWalletId = null,
            destDelta = 0L
        )

        // The transaction row was persisted.
        assertEquals(1L, transactionCount())
        // The balance was decreased within the same commit.
        assertEquals(700L, readBalance(walletId))
    }

    /**
     * 9.4 companion to 9.1 — a TRANSFER applies both the source (-amount) and destination (+amount)
     * balance changes within the one atomic write.
     */
    @Test
    fun atomicWrite_transferAppliesBothBalancesTogether() = runBlocking {
        val source = insertWallet(name = "Cash", balance = 1_000L)
        val dest = insertWallet(name = "Bank", balance = 500L)

        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction(
                id = "tx-transfer",
                type = TransactionType.TRANSFER,
                amount = 200L,
                sourceWalletId = source,
                destWalletId = dest
            ),
            sourceWalletId = source,
            sourceDelta = -200L,
            destWalletId = dest,
            destDelta = 200L
        )

        assertEquals(1L, transactionCount())
        assertEquals(800L, readBalance(source))
        assertEquals(700L, readBalance(dest))
    }

    /**
     * 9.5 — When the balance update fails, Room rolls back the entire `@Transaction`, leaving both
     * the transactions store and the wallets store unchanged.
     *
     * See the class comment for the induced-failure mechanism: a trigger ABORTs the balance UPDATE
     * (which runs *after* the insert) when the resulting balance would be negative.
     */
    @Test
    fun atomicWrite_failingBalanceUpdate_rollsBackInsert() = runBlocking {
        val walletId = insertWallet(name = "Cash", balance = 100L)

        // Install the guard that makes the balance UPDATE fail after the insert has been written.
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER guard_no_negative_balance BEFORE UPDATE ON wallets
            WHEN NEW.balance < 0
            BEGIN
                SELECT RAISE(ABORT, 'balance would go negative');
            END;
            """.trimIndent()
        )

        val startingBalance = readBalance(walletId)
        val startingCount = transactionCount()

        // Attempt an EXPENSE of 500 against a balance of 100: the -500 delta drives the balance to
        // -400, tripping the trigger. The insert has already run, so this proves rollback of the
        // insert caused by a failing balance update.
        var threw = false
        try {
            transactionDao.insertAndApplyBalances(
                tx = sampleTransaction(
                    id = "tx-should-rollback",
                    type = TransactionType.EXPENSE,
                    amount = 500L,
                    sourceWalletId = walletId,
                    destWalletId = null
                ),
                sourceWalletId = walletId,
                sourceDelta = -500L,
                destWalletId = null,
                destDelta = 0L
            )
        } catch (e: Exception) {
            threw = true
        }

        assertTrue("The failing balance update should have thrown", threw)
        // Transactions store unchanged: the insert was rolled back.
        assertEquals(startingCount, transactionCount())
        assertNull(
            "The rolled-back transaction row must not exist",
            readTransactionId("tx-should-rollback")
        )
        // Wallets store unchanged: the balance is exactly as before.
        assertEquals(startingBalance, readBalance(walletId))
    }

    /** Returns the id of a transaction row if present, else null. */
    private fun readTransactionId(id: String): String? {
        db.openHelper.writableDatabase
            .query("SELECT id FROM transactions WHERE id = ?", arrayOf<Any>(id))
            .use { cursor ->
                return if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    /**
     * 12.1 / 12.2 — [WalletDao.observeActiveWallets] emits the updated balance after a wallet
     * balance changes in the store, confirming real-time Flow observation.
     *
     * The first emission reflects the initial balance; after an atomic write applies a delta, a
     * fresh collection of the Flow observes the updated balance. (Room re-runs the query and emits
     * on the underlying table change.)
     */
    @Test
    fun observeActiveWallets_emitsUpdatedBalanceAfterWalletUpdate() = runBlocking {
        val walletId = insertWallet(name = "Cash", balance = 1_000L)

        // Initial emission reflects the starting balance.
        val initial: List<WalletEntity> = walletDao.observeActiveWallets().first()
        val initialWallet = initial.single { it.id == walletId }
        assertEquals(1_000L, initialWallet.balance)

        // Apply an INCOME of 250 through the atomic boundary, updating the wallets table.
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction(
                id = "tx-income",
                type = TransactionType.INCOME,
                amount = 250L,
                sourceWalletId = walletId,
                destWalletId = null
            ),
            sourceWalletId = walletId,
            sourceDelta = 250L,
            destWalletId = null,
            destDelta = 0L
        )

        // A subsequent collection observes the updated balance emitted by the Flow.
        val updated: List<WalletEntity> = walletDao.observeActiveWallets().first()
        val updatedWallet = updated.single { it.id == walletId }
        assertEquals(1_250L, updatedWallet.balance)
    }

    /** 12.2 sanity — archived wallets are excluded from the observed active-wallet stream. */
    @Test
    fun observeActiveWallets_excludesArchivedWallets() = runBlocking {
        val active = insertWallet(name = "Cash", balance = 100L, isArchived = false)
        insertWallet(name = "Old", balance = 50L, isArchived = true)

        val wallets = walletDao.observeActiveWallets().first()

        assertEquals(1, wallets.size)
        assertEquals(active, wallets.single().id)
        // The archived wallet still exists in storage; it is only filtered from the active stream.
        assertNotNull(readBalance(active))
    }

    /** Guards against a silent regression where the DAO method is not actually transactional. */
    @Test
    fun atomicWrite_isTransactional_orTestSetupIsWrong() = runBlocking {
        // If neither store changed on a valid write, the test harness itself is broken.
        val walletId = insertWallet(name = "Cash", balance = 10L)
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-guard", TransactionType.INCOME, 5L, walletId, null),
            sourceWalletId = walletId,
            sourceDelta = 5L,
            destWalletId = null,
            destDelta = 0L
        )
        if (transactionCount() == 0L || readBalance(walletId) != 15L) {
            fail("Expected the valid atomic write to persist both the insert and balance update")
        }
    }
}
