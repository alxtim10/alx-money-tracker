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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **transaction-store integrity under metadata changes**
 * (Task 7.2, Property 1).
 *
 * ```
 * // Feature: customization-metadata, Property 1: No metadata operation ever mutates the transactions store
 * ```
 *
 * *For any* contents of the `Transactions_Store` and *any* metadata operation (create / rename /
 * update / override / archive / delete / set-default on any Wallet, Category, or Quick_Preset),
 * after the operation the `Transactions_Store` contains exactly the same number of `Transaction`
 * records as before and every field value of every existing `Transaction` record is identical to
 * its value before the operation. In particular, a `Direct_Balance_Override` adds no `Transaction`
 * record (Requirements 4.3, 4.4, 5.4, 9.5, 10.4, 14.2, 15.1, 15.2, 15.3).
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Transactions_Store` (the Room `transactions` table), so
 * it must run against an actual Room database rather than a fake. Following the conventions of the
 * existing Scope 1 & 2 Room tests (`TransactionDaoAtomicityTest`,
 * `RoomHistoryRepositoryReadIsolationTest`), it builds a real [AppDatabase] with
 * [Room.inMemoryDatabaseBuilder] + [androidx.room.RoomDatabase.Builder.allowMainThreadQueries],
 * runs suspend/Flow calls under [runBlocking] on the test thread, and drives every seed insert
 * through Scope 1's write path ([TransactionDao.insertAndApplyBalances]). The metadata operations
 * are exercised through the real [RoomMetadataRepository] over the real metadata DAOs, so the
 * structural boundary (no `TransactionDao` dependency) is verified end to end.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`), so — matching `RoomHistoryRepositoryReadIsolationTest` —
 * this test generates its inputs with a seeded [kotlin.random.Random]. It runs [ITERATIONS]
 * independent cases (>= 100, per the design's minimum-iterations convention). Each case seeds a
 * fresh store with a randomly generated set of transactions plus a pool of metadata records, then
 * runs one randomly chosen metadata operation. The seeded RNG makes any failure deterministically
 * reproducible from the printed seed. Every one of the seven operation kinds
 * (create/rename/update/override/archive/delete/set-default) is additionally forced at least once
 * via [forcedOperations] so no kind can be skipped by chance.
 *
 * ### Snapshot comparison
 * Before and after each metadata operation, the `transactions` table is snapshotted by reading
 * every row directly via raw SQL (bypassing the repository, so the assertion does not depend on the
 * code under test). The snapshot captures every field of every row. The test asserts the row count
 * is unchanged and every field of every row is identical.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryTransactionIntegrityTest {

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

    /** The seven metadata operation kinds; each is forced at least once and then chosen at random. */
    private enum class Op {
        CREATE_WALLET,
        RENAME_WALLET,
        OVERRIDE_BALANCE,
        ARCHIVE_WALLET,
        SET_DEFAULT_WALLET,
        CREATE_CATEGORY,
        UPDATE_CATEGORY,
        ARCHIVE_CATEGORY,
        CREATE_PRESET,
        UPDATE_PRESET,
        DELETE_PRESET
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

    /**
     * Property 1 — no metadata operation ever mutates the transactions store.
     *
     * Runs [ITERATIONS] randomized cases. Each case:
     * 1. seeds a fresh store with a random set of transactions (via Scope 1's write path) plus a
     *    pool of wallets / categories / presets to operate on,
     * 2. snapshots every transaction row/field,
     * 3. runs one metadata operation through [RoomMetadataRepository],
     * 4. re-snapshots and asserts the count and every field are unchanged.
     *
     * The first [Op] values are forced so every operation kind is exercised regardless of RNG.
     */
    @Test
    fun noMetadataOperationMutatesTheTransactionsStore() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        val forcedOperations = Op.entries.toList()

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            val op = if (iteration < forcedOperations.size) {
                forcedOperations[iteration]
            } else {
                Op.entries.toTypedArray().random(outerRng)
            }
            try {
                runCase(Random(caseSeed), op)
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 1 failed on iteration $iteration op=$op " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runCase(rng: Random, op: Op) = runBlocking {
        // --- Seed a pool of wallets and categories to reference and to operate on. ---
        val walletCount = rng.nextInt(1, 5)
        val walletIds = (0 until walletCount).map { i ->
            // Vary names (including case variants) so default-reassignment ordering is exercised.
            insertWallet(
                name = "W$i-${randomName(rng)}",
                balance = rng.nextLong(0, 5_000_000),
                isDefault = i == 0,
                isArchived = false
            )
        }
        val categoryCount = rng.nextInt(1, 4)
        val categoryIds = (0 until categoryCount).map { i ->
            insertCategory(name = "C$i", type = randomCategoryType(rng))
        }
        val presetCount = rng.nextInt(1, 4)
        val presetIds = (0 until presetCount).map { i ->
            insertPreset(amount = rng.nextLong(1, 1_000_000), label = "P$i")
        }

        // --- Seed a random set of transactions via Scope 1's atomic write path. ---
        val txCount = rng.nextInt(0, 12)
        repeat(txCount) { i ->
            val type = randomType(rng)
            val amount = rng.nextLong(0, 500_000)
            val source = walletIds.random(rng)
            val dest = if (type == TransactionType.TRANSFER) walletIds.random(rng) else null
            val tx = Transaction(
                id = "tx-$i-${rng.nextLong()}",
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

        // --- Snapshot BEFORE the metadata operation. ---
        val before = snapshotStore()

        // --- Run exactly one metadata operation through the repository. ---
        applyOperation(rng, op, walletIds, categoryIds, presetIds)

        // --- Snapshot AFTER; assert count and every field unchanged. ---
        val after = snapshotStore()

        assertEquals(
            "Metadata op $op changed the transaction row count",
            before.size,
            after.size
        )
        assertEquals(
            "Metadata op $op changed the set of transaction ids",
            before.keys,
            after.keys
        )
        for ((id, beforeRow) in before) {
            assertEquals(
                "Metadata op $op changed a field of transaction $id",
                beforeRow,
                after[id]
            )
        }
    }

    /** Dispatches the chosen [op] to the real repository with randomly generated valid inputs. */
    private suspend fun applyOperation(
        rng: Random,
        op: Op,
        walletIds: List<Long>,
        categoryIds: List<Long>,
        presetIds: List<Long>
    ) {
        when (op) {
            Op.CREATE_WALLET ->
                repository.createWallet(
                    NewWallet(name = randomName(rng), balance = rng.nextLong(0, MAX_MONEY))
                )

            Op.RENAME_WALLET ->
                repository.renameWallet(walletIds.random(rng), randomName(rng))

            Op.OVERRIDE_BALANCE ->
                repository.overrideBalance(walletIds.random(rng), rng.nextLong(0, MAX_MONEY))

            Op.ARCHIVE_WALLET ->
                repository.archiveWallet(walletIds.random(rng))

            Op.SET_DEFAULT_WALLET ->
                repository.setDefaultWallet(walletIds.random(rng))

            Op.CREATE_CATEGORY ->
                repository.createCategory(
                    CategoryFields(
                        name = randomName(rng),
                        type = randomCategoryType(rng),
                        icon = if (rng.nextBoolean()) "icon-${rng.nextInt(100)}" else null
                    )
                )

            Op.UPDATE_CATEGORY ->
                repository.updateCategory(
                    categoryIds.random(rng),
                    CategoryPatch(
                        name = if (rng.nextBoolean()) randomName(rng) else null,
                        type = if (rng.nextBoolean()) randomCategoryType(rng) else null,
                        icon = if (rng.nextBoolean()) "icon-${rng.nextInt(100)}" else null
                    )
                )

            Op.ARCHIVE_CATEGORY ->
                repository.archiveCategory(categoryIds.random(rng))

            Op.CREATE_PRESET ->
                repository.createPreset(
                    PresetFields(amount = rng.nextLong(1, MAX_MONEY), label = randomName(rng))
                )

            Op.UPDATE_PRESET ->
                repository.updatePreset(
                    presetIds.random(rng),
                    PresetPatch(
                        amount = if (rng.nextBoolean()) rng.nextLong(1, MAX_MONEY) else null,
                        label = if (rng.nextBoolean()) randomName(rng) else null
                    )
                )

            Op.DELETE_PRESET ->
                repository.deletePreset(presetIds.random(rng))
        }
    }

    // region Raw-SQL seeding + snapshotting (bypasses the code under test)

    /** Inserts a wallet directly via raw SQL and returns its generated row id. */
    private fun insertWallet(
        name: String,
        balance: Long,
        isDefault: Boolean,
        isArchived: Boolean
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

    /** Inserts a quick preset directly via raw SQL and returns its generated row id. */
    private fun insertPreset(amount: Long, label: String): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO quick_presets (amount, label) VALUES (?, ?)",
            arrayOf<Any>(amount, label)
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

    /** Removes all rows so the next case starts from a clean, independently generated state. */
    private fun clearStore() {
        val helper = db.openHelper.writableDatabase
        helper.execSQL("DELETE FROM transactions")
        helper.execSQL("DELETE FROM wallets")
        helper.execSQL("DELETE FROM categories")
        helper.execSQL("DELETE FROM quick_presets")
    }

    // endregion

    // region Generators

    private fun randomType(rng: Random): TransactionType =
        TransactionType.entries.toTypedArray().random(rng)

    /** A category type is always INCOME or EXPENSE (never TRANSFER) so validation accepts it. */
    private fun randomCategoryType(rng: Random): TransactionType =
        if (rng.nextBoolean()) TransactionType.INCOME else TransactionType.EXPENSE

    /** A valid non-empty name/label within the tightest bound (50) shared across entities. */
    private fun randomName(rng: Random): String {
        val length = rng.nextInt(1, 40)
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9') + ' '
        // Guarantee a non-whitespace char so the trimmed name is non-empty.
        return "x" + (1 until length).joinToString("") { chars.random(rng).toString() }
    }

    // endregion

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100

        /** Shared money ceiling from the validator (999_999_999_999). */
        const val MAX_MONEY = 999_999_999_999L
    }
}
