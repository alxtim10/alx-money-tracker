package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.domain.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **Property 3** (Task 7.4).
 *
 * ```
 * // Feature: customization-metadata, Property 3: Archiving an already-archived record is a no-op
 * ```
 *
 * *For any* Wallet or Category whose `is_archived` value is already true, archiving it again
 * leaves the `Wallets_Store` / `Categories_Store`, the `Default_Wallet` designation, and the
 * `Transactions_Store` unchanged.
 *
 * **Validates: Requirements 5.7, 10.5**
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* stores (the Room `wallets`, `categories`, and
 * `transactions` tables), so it exercises the actual [RoomMetadataRepository] over a real
 * [AppDatabase] rather than a fake. It follows the conventions of the existing Room tests
 * ([RoomHistoryRepositoryReadIsolationTest], [com.alx.moneytracker.data.local.TransactionDaoAtomicityTest]):
 * a real database built with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries], suspend calls under [runBlocking],
 * and raw-SQL snapshots taken independently of the code under test.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`), so — matching [RoomHistoryRepositoryReadIsolationTest] —
 * this test generates its inputs with a seeded [kotlin.random.Random] and runs [ITERATIONS]
 * independent cases. The seeded RNG makes any failure deterministically reproducible from the
 * printed seed.
 *
 * ### Each case
 * 1. Seeds a random store: several wallets (some active, some already archived, exactly one active
 *    default when any active wallet exists), several categories (some active, some already
 *    archived), and a handful of `transactions` rows referencing the seeded wallets/categories.
 * 2. Picks an **already-archived** wallet and an **already-archived** category as the archive
 *    targets (guaranteeing at least one of each is present).
 * 3. Snapshots every row of all three stores via raw SQL.
 * 4. Invokes [RoomMetadataRepository.archiveWallet] and [RoomMetadataRepository.archiveCategory]
 *    on the already-archived targets; both are expected to succeed as no-ops.
 * 5. Re-snapshots and asserts all three stores are byte-for-byte unchanged — including the
 *    `is_default` designation across wallets and every `transactions` field.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryArchiveNoOpTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var quickPresetDao: QuickPresetDao
    private lateinit var repository: RoomMetadataRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        categoryDao = db.categoryDao()
        quickPresetDao = db.quickPresetDao()
        repository = RoomMetadataRepository(
            walletDao = walletDao,
            categoryDao = categoryDao,
            quickPresetDao = quickPresetDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // region Raw-SQL snapshots (independent of the code under test)

    /** A full snapshot of one `wallets` row; equality covers every field. */
    private data class WalletRow(
        val id: Long,
        val name: String,
        val balance: Long,
        val isDefault: Int,
        val isArchived: Int
    )

    /** A full snapshot of one `categories` row; equality covers every field. */
    private data class CategoryRow(
        val id: Long,
        val name: String,
        val type: String,
        val icon: String,
        val isArchived: Int
    )

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

    private fun snapshotWallets(): Map<Long, WalletRow> {
        val rows = mutableMapOf<Long, WalletRow>()
        db.openHelper.writableDatabase.query(
            "SELECT id, name, balance, is_default, is_archived FROM wallets"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                rows[id] = WalletRow(
                    id = id,
                    name = cursor.getString(1),
                    balance = cursor.getLong(2),
                    isDefault = cursor.getInt(3),
                    isArchived = cursor.getInt(4)
                )
            }
        }
        return rows
    }

    private fun snapshotCategories(): Map<Long, CategoryRow> {
        val rows = mutableMapOf<Long, CategoryRow>()
        db.openHelper.writableDatabase.query(
            "SELECT id, name, type, icon, is_archived FROM categories"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                rows[id] = CategoryRow(
                    id = id,
                    name = cursor.getString(1),
                    type = cursor.getString(2),
                    icon = cursor.getString(3),
                    isArchived = cursor.getInt(4)
                )
            }
        }
        return rows
    }

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

    // endregion

    // region Raw-SQL seed helpers (bypass the repository so seeding is independent of it)

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

    private fun insertCategory(
        name: String,
        type: TransactionType,
        icon: String,
        isArchived: Boolean
    ): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO categories (name, type, icon, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, type.name, icon, if (isArchived) 1 else 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun insertTransaction(
        id: String,
        timestamp: Long,
        type: TransactionType,
        amount: Long,
        sourceWalletId: Long,
        destWalletId: Long?,
        categoryId: Long,
        note: String,
        isSynced: Int
    ) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO transactions (id, timestamp, type, amount, source_wallet, dest_wallet, category_id, note, is_synced) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                id,
                timestamp,
                type.name,
                amount,
                sourceWalletId,
                destWalletId,
                categoryId,
                note,
                isSynced
            )
        )
    }

    private fun clearStore() {
        val helper = db.openHelper.writableDatabase
        helper.execSQL("DELETE FROM transactions")
        helper.execSQL("DELETE FROM wallets")
        helper.execSQL("DELETE FROM categories")
    }

    private fun randomType(rng: Random): TransactionType =
        TransactionType.entries.toTypedArray().random(rng)

    // endregion

    /**
     * Property 3 — archiving an already-archived wallet or category is a no-op across all three
     * stores (wallets, categories, transactions), including the default-wallet designation.
     */
    @Test
    fun archivingAlreadyArchivedRecord_isANoOp() {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runNoOpCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 3 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runNoOpCase(rng: Random) = runBlocking {
        // --- Seed wallets: some active, some archived, at most one active default. ---
        // Guarantee at least one already-archived wallet (the archive target).
        val archivedWalletCount = rng.nextInt(1, 4)
        val activeWalletCount = rng.nextInt(0, 4)

        val activeWalletIds = mutableListOf<Long>()
        // The first active wallet (if any) carries the single default designation.
        repeat(activeWalletCount) { i ->
            activeWalletIds += insertWallet(
                name = "AW$i-${rng.nextInt(1000)}",
                balance = rng.nextLong(0, 1_000_000),
                isDefault = i == 0,
                isArchived = false
            )
        }
        val archivedWalletIds = mutableListOf<Long>()
        repeat(archivedWalletCount) { i ->
            archivedWalletIds += insertWallet(
                name = "ZW$i-${rng.nextInt(1000)}",
                balance = rng.nextLong(0, 1_000_000),
                isDefault = false,
                isArchived = true
            )
        }

        // --- Seed categories: some active, at least one already archived (the archive target). ---
        val archivedCategoryCount = rng.nextInt(1, 4)
        val activeCategoryCount = rng.nextInt(0, 4)

        val allCategoryIds = mutableListOf<Long>()
        repeat(activeCategoryCount) { i ->
            allCategoryIds += insertCategory(
                name = "AC$i",
                type = randomType(rng),
                icon = "icon-$i",
                isArchived = false
            )
        }
        val archivedCategoryIds = mutableListOf<Long>()
        repeat(archivedCategoryCount) { i ->
            val id = insertCategory(
                name = "ZC$i",
                type = randomType(rng),
                icon = "icon-z$i",
                isArchived = true
            )
            archivedCategoryIds += id
            allCategoryIds += id
        }

        // --- Seed some transactions referencing seeded wallets/categories. ---
        val allWalletIds = activeWalletIds + archivedWalletIds
        val txCount = rng.nextInt(0, 8)
        repeat(txCount) { i ->
            val type = randomType(rng)
            val source = allWalletIds.random(rng)
            val dest = if (type == TransactionType.TRANSFER && allWalletIds.size > 1) {
                allWalletIds.random(rng)
            } else {
                null
            }
            insertTransaction(
                id = "tx-$i-${rng.nextLong()}",
                timestamp = rng.nextLong(0, 10_000_000),
                type = type,
                amount = rng.nextLong(0, 500_000),
                sourceWalletId = source,
                destWalletId = dest,
                categoryId = allCategoryIds.random(rng),
                note = if (rng.nextBoolean()) "note-$i" else "",
                isSynced = if (rng.nextBoolean()) 1 else 0
            )
        }

        // --- Snapshot BEFORE archiving the already-archived targets. ---
        val walletsBefore = snapshotWallets()
        val categoriesBefore = snapshotCategories()
        val transactionsBefore = snapshotTransactions()

        // --- Archive an already-archived wallet and an already-archived category. ---
        val walletTarget = archivedWalletIds.random(rng)
        val categoryTarget = archivedCategoryIds.random(rng)

        val walletResult = repository.archiveWallet(walletTarget)
        val categoryResult = repository.archiveCategory(categoryTarget)

        // Both no-op archives are expected to complete successfully.
        assertEquals(
            "Re-archiving an already-archived wallet should succeed as a no-op",
            true,
            walletResult.isSuccess
        )
        assertEquals(
            "Re-archiving an already-archived category should succeed as a no-op",
            true,
            categoryResult.isSuccess
        )

        // --- Snapshot AFTER; assert all three stores are unchanged. ---
        val walletsAfter = snapshotWallets()
        val categoriesAfter = snapshotCategories()
        val transactionsAfter = snapshotTransactions()

        assertEquals("Wallets store changed", walletsBefore, walletsAfter)
        assertEquals("Categories store changed", categoriesBefore, categoriesAfter)
        assertEquals("Transactions store changed", transactionsBefore, transactionsAfter)

        // Explicitly assert the Default_Wallet designation is unchanged (Requirement 5.7): the set
        // of wallets whose is_default = 1 is identical before and after.
        val defaultsBefore = walletsBefore.values.filter { it.isDefault == 1 }.map { it.id }.toSet()
        val defaultsAfter = walletsAfter.values.filter { it.isDefault == 1 }.map { it.id }.toSet()
        assertEquals("Default_Wallet designation changed", defaultsBefore, defaultsAfter)
    }

    private companion object {
        /** Iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
