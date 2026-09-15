package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.WalletDao
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
import kotlin.random.Random

/**
 * Room in-memory property test for **Property 2** (Task 7.3).
 *
 * ```
 * // Feature: customization-metadata, Property 2: Archiving is a soft delete that preserves the record
 * ```
 *
 * *For any* Wallet or Category whose `is_archived` value is false, archiving it sets that record's
 * `is_archived` value to true while retaining the record in its store (the store's record count is
 * unchanged and the record remains retrievable by its identifier), and thereafter the record is
 * excluded from the collection of active records exposed through the `Observable_Stream`.
 *
 * **Validates: Requirements 5.1, 5.2, 10.1, 10.2**
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Wallets_Store` / `Categories_Store` (the Room `wallets`
 * and `categories` tables) and over the active `Observable_Stream` exposed by
 * [RoomMetadataRepository]. It therefore runs against an actual [AppDatabase] rather than a fake,
 * following the conventions of the existing Scope 1 & 2 Room tests
 * (`TransactionDaoAtomicityTest`, `RoomHistoryRepositoryReadIsolationTest`): a real in-memory
 * database built with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries], with suspend/Flow calls driven under
 * [runBlocking] on the test thread.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`), so — as with the other Room-backed property tests in
 * this module — inputs are generated with a seeded [kotlin.random.Random]. The test runs
 * [ITERATIONS] independent cases; each seeds a fresh set of non-archived wallets and categories,
 * archives one of each through the repository, and asserts the soft-delete characterization. The
 * seeded RNG makes any failure deterministically reproducible from the printed seed.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryArchiveSoftDeleteTest {

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
        // The repository runs its suspend writes on the injected dispatcher; use an unconfined-like
        // direct dispatcher so runBlocking on the test thread observes each write synchronously.
        repository = RoomMetadataRepository(
            walletDao = walletDao,
            categoryDao = categoryDao,
            quickPresetDao = quickPresetDao,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // region raw-SQL helpers (bypass the code under test for independent snapshots)

    /** Inserts a non-archived wallet directly via raw SQL and returns its generated row id. */
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

    /** Inserts a non-archived category directly via raw SQL and returns its generated row id. */
    private fun insertCategory(name: String, type: TransactionType, icon: String): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO categories (name, type, icon, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, type.name, icon, 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Total number of `wallets` rows (archived and active alike). */
    private fun walletRowCount(): Int = tableRowCount("wallets")

    /** Total number of `categories` rows (archived and active alike). */
    private fun categoryRowCount(): Int = tableRowCount("categories")

    private fun tableRowCount(table: String): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    /** Reads the `is_archived` flag of a wallet row directly, or null when the row is absent. */
    private fun walletArchivedFlag(id: Long): Boolean? = archivedFlag("wallets", id)

    /** Reads the `is_archived` flag of a category row directly, or null when the row is absent. */
    private fun categoryArchivedFlag(id: Long): Boolean? = archivedFlag("categories", id)

    private fun archivedFlag(table: String, id: Long): Boolean? {
        db.openHelper.writableDatabase.query(
            "SELECT is_archived FROM $table WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getInt(0) != 0
        }
    }

    private fun clearStore() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM wallets")
        db.openHelper.writableDatabase.execSQL("DELETE FROM categories")
    }

    // endregion

    /**
     * Property 2 — archiving is a soft delete that preserves the record.
     *
     * Runs [ITERATIONS] randomized cases. Each case:
     * 1. seeds a fresh store with a random set of non-archived wallets and categories,
     * 2. archives one randomly chosen wallet and one randomly chosen category through the
     *    repository,
     * 3. asserts for each archived record that: its `is_archived` flag flipped to true; the store's
     *    total row count is unchanged (soft delete, not a hard delete); the row remains retrievable
     *    by id; and it is excluded from the active `Observable_Stream` while every non-archived peer
     *    is still present.
     */
    @Test
    fun archiving_isSoftDelete_preservesRecordAndExcludesFromActiveStream() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runArchiveCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 2 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runArchiveCase(rng: Random) = runBlocking {
        // --- Seed a random set of non-archived wallets. Exactly one is the default so the store is
        // in a realistic exactly-one-default state; archiving is verified regardless of which
        // wallet is chosen. ---
        val walletCount = rng.nextInt(1, 6)
        val defaultIndex = rng.nextInt(walletCount)
        val walletIds = (0 until walletCount).map { i ->
            insertWallet(
                name = "W$i-${rng.nextInt(1000)}",
                balance = rng.nextLong(0, 1_000_000),
                isDefault = i == defaultIndex
            )
        }

        // --- Seed a random set of non-archived categories (INCOME or EXPENSE only). ---
        val categoryCount = rng.nextInt(1, 6)
        val categoryIds = (0 until categoryCount).map { i ->
            insertCategory(
                name = "C$i-${rng.nextInt(1000)}",
                type = if (rng.nextBoolean()) TransactionType.INCOME else TransactionType.EXPENSE,
                icon = if (rng.nextBoolean()) "icon-$i" else ""
            )
        }

        val walletCountBefore = walletRowCount()
        val categoryCountBefore = categoryRowCount()

        // ---- Archive one wallet. ----
        val targetWalletId = walletIds.random(rng)
        assertFalse(
            "Precondition: target wallet must start non-archived",
            walletArchivedFlag(targetWalletId) ?: error("target wallet missing before archive")
        )
        val walletResult = repository.archiveWallet(targetWalletId)
        assertTrue("archiveWallet should succeed", walletResult.isSuccess)

        // is_archived flipped to true (Req 5.1).
        assertEquals(
            "Archived wallet's is_archived flag should be true",
            true,
            walletArchivedFlag(targetWalletId)
        )
        // The record is retained, not deleted: total row count unchanged (Req 5.2)...
        assertEquals(
            "Archiving a wallet must not change the total wallets row count (soft delete)",
            walletCountBefore,
            walletRowCount()
        )
        // ...and it remains retrievable by its identifier (Req 5.2).
        assertNotNull(
            "Archived wallet must remain retrievable by id",
            walletDao.findById(targetWalletId)
        )

        // Excluded from the active Observable_Stream (Req 5.1 selection behavior); every other
        // non-archived wallet is still present.
        val activeWalletIds = repository.observeWallets().first().map { it.id }.toSet()
        assertFalse(
            "Archived wallet must be excluded from the active wallet stream",
            activeWalletIds.contains(targetWalletId)
        )
        assertEquals(
            "Every non-archived wallet must still be present in the active stream",
            walletIds.filter { it != targetWalletId }.toSet(),
            activeWalletIds
        )

        // ---- Archive one category. ----
        val targetCategoryId = categoryIds.random(rng)
        assertFalse(
            "Precondition: target category must start non-archived",
            categoryArchivedFlag(targetCategoryId) ?: error("target category missing before archive")
        )
        val categoryResult = repository.archiveCategory(targetCategoryId)
        assertTrue("archiveCategory should succeed", categoryResult.isSuccess)

        // is_archived flipped to true (Req 10.1).
        assertEquals(
            "Archived category's is_archived flag should be true",
            true,
            categoryArchivedFlag(targetCategoryId)
        )
        // The record is retained, not deleted: total row count unchanged (Req 10.2)...
        assertEquals(
            "Archiving a category must not change the total categories row count (soft delete)",
            categoryCountBefore,
            categoryRowCount()
        )
        // ...and it remains retrievable by its identifier (Req 10.2).
        assertNotNull(
            "Archived category must remain retrievable by id",
            categoryDao.findById(targetCategoryId)
        )

        // Excluded from the active Observable_Stream (Req 10.1 selection behavior); every other
        // non-archived category is still present.
        val activeCategoryIds = repository.observeCategories().first().map { it.id }.toSet()
        assertFalse(
            "Archived category must be excluded from the active category stream",
            activeCategoryIds.contains(targetCategoryId)
        )
        assertEquals(
            "Every non-archived category must still be present in the active stream",
            categoryIds.filter { it != targetCategoryId }.toSet(),
            activeCategoryIds
        )
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
