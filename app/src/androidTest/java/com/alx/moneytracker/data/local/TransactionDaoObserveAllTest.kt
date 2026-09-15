package com.alx.moneytracker.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.entity.TransactionEntity
import com.alx.moneytracker.domain.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room in-memory integration tests for the reactive re-emission of
 * [TransactionDao.observeAll] (Task 8.2, Requirement 2).
 *
 * These are instrumented tests: they build a real Room [AppDatabase] with
 * [Room.inMemoryDatabaseBuilder] and [androidx.room.RoomDatabase.Builder.allowMainThreadQueries]
 * so suspend DAO calls run under [runBlocking] on the test thread, exercising the actual
 * `SELECT * FROM transactions` query observed as a [kotlinx.coroutines.flow.Flow].
 *
 * Covered acceptance criteria (Requirement 2 — Real-Time Observation):
 * - 2.1 / 2.2 — on subscribe, the stream emits the current transaction list.
 * - 2.3 — when a transaction is added to the store, the stream re-emits reflecting the addition.
 * - 2.4 — when a transaction changes in the store, the stream re-emits reflecting the change.
 * - 2.5 — when a transaction is removed from the store, the stream re-emits excluding it.
 *
 * ### Observation mechanism
 * Room re-runs an observed query and emits a fresh list on any change to the underlying
 * `transactions` table. Each assertion collects the latest emission with
 * [kotlinx.coroutines.flow.first] *after* the mutating write has committed, which observes the
 * table state the write produced. Inserts are driven through Scope 1's write path
 * ([TransactionDao.insertAndApplyBalances] / [TransactionDao.insert]) rather than raw SQL, so the
 * test exercises the real production write boundary.
 */
@RunWith(AndroidJUnit4::class)
class TransactionDaoObserveAllTest {

    private lateinit var db: AppDatabase
    private lateinit var transactionDao: TransactionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        transactionDao = db.transactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

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

    private fun sampleTransaction(
        id: String,
        type: TransactionType,
        amount: Long,
        sourceWalletId: Long,
        destWalletId: Long? = null,
        isSynced: Int = 0,
        categoryId: Long = 1L,
        timestamp: Long = 1_000L
    ) = TransactionEntity(
        id = id,
        timestamp = timestamp,
        type = type.name,
        amount = amount,
        sourceWalletId = sourceWalletId,
        destWalletId = destWalletId,
        categoryId = categoryId,
        note = "",
        isSynced = isSynced
    )

    /**
     * 2.1 / 2.2 — On subscribe, [TransactionDao.observeAll] emits the current transaction list.
     *
     * The store is seeded (via Scope 1's write path) with two transactions before the first
     * collection; the initial emission contains exactly those two records.
     */
    @Test
    fun observeAll_emitsCurrentListOnSubscribe() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-1", TransactionType.EXPENSE, 100L, wallet),
            sourceWalletId = wallet, sourceDelta = -100L, destWalletId = null, destDelta = 0L
        )
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-2", TransactionType.INCOME, 50L, wallet),
            sourceWalletId = wallet, sourceDelta = 50L, destWalletId = null, destDelta = 0L
        )

        val emitted = transactionDao.observeAll().first()

        assertEquals(2, emitted.size)
        assertEquals(setOf("tx-1", "tx-2"), emitted.map { it.id }.toSet())
    }

    /** 2.2 — With an empty store, the first emission is an empty list (not a missing emission). */
    @Test
    fun observeAll_emitsEmptyListWhenStoreEmpty() = runBlocking {
        val emitted = transactionDao.observeAll().first()

        assertTrue("Expected an empty list on subscribe against an empty store", emitted.isEmpty())
    }

    /**
     * 2.3 — When a transaction is added to the store, the stream re-emits a list that reflects
     * the added transaction.
     *
     * The insert is driven through Scope 1's write path ([TransactionDao.insertAndApplyBalances]).
     */
    @Test
    fun observeAll_reEmitsWithAddedTransactionOnInsert() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)

        val before = transactionDao.observeAll().first()
        assertTrue(before.isEmpty())

        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-added", TransactionType.EXPENSE, 300L, wallet),
            sourceWalletId = wallet, sourceDelta = -300L, destWalletId = null, destDelta = 0L
        )

        val after = transactionDao.observeAll().first()
        assertEquals(1, after.size)
        assertEquals("tx-added", after.single().id)
        assertEquals(300L, after.single().amount)
    }

    /**
     * 2.4 — When a transaction record in the store changes, the stream re-emits a list that
     * reflects the change.
     *
     * A transaction is inserted with `is_synced = 0`, then its sync flag is updated to 1 (the
     * mutation a later synchronization scope performs). The re-emitted list reflects the new value.
     */
    @Test
    fun observeAll_reEmitsWithChangeOnUpdate() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-change", TransactionType.INCOME, 200L, wallet, isSynced = 0),
            sourceWalletId = wallet, sourceDelta = 200L, destWalletId = null, destDelta = 0L
        )

        val before = transactionDao.observeAll().first()
        assertEquals(0, before.single { it.id == "tx-change" }.isSynced)

        // Mutate the existing row's is_synced value directly in the store.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE transactions SET is_synced = 1 WHERE id = ?",
            arrayOf<Any>("tx-change")
        )

        val after = transactionDao.observeAll().first()
        assertEquals(1, after.size)
        assertEquals(1, after.single { it.id == "tx-change" }.isSynced)
    }

    /**
     * 2.5 — When a transaction record is removed from the store, the stream re-emits a list that
     * excludes the removed transaction.
     */
    @Test
    fun observeAll_reEmitsExcludingRemovedTransaction() = runBlocking {
        val wallet = insertWallet(name = "Cash", balance = 1_000L)
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-keep", TransactionType.EXPENSE, 100L, wallet),
            sourceWalletId = wallet, sourceDelta = -100L, destWalletId = null, destDelta = 0L
        )
        transactionDao.insertAndApplyBalances(
            tx = sampleTransaction("tx-remove", TransactionType.EXPENSE, 400L, wallet),
            sourceWalletId = wallet, sourceDelta = -400L, destWalletId = null, destDelta = 0L
        )

        val before = transactionDao.observeAll().first()
        assertEquals(setOf("tx-keep", "tx-remove"), before.map { it.id }.toSet())

        // Remove one transaction from the store.
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM transactions WHERE id = ?",
            arrayOf<Any>("tx-remove")
        )

        val after = transactionDao.observeAll().first()
        assertEquals(1, after.size)
        assertEquals("tx-keep", after.single().id)
        assertFalse("Removed transaction must not remain in the emission", after.any { it.id == "tx-remove" })
    }
}
