package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.logic.MetadataValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **valid-creation persistence** (Task 7.9).
 *
 * ```
 * // Feature: customization-metadata, Property 10: A valid creation persists exactly the submitted fields
 * ```
 *
 * *For any* valid wallet / category / preset submission, creating it through
 * [RoomMetadataRepository] persists a record that carries exactly the submitted fields: the
 * (already-trimmed) name/label, the submitted balance/amount, the submitted type and icon (for a
 * category), and `is_archived = false` for wallets and categories.
 *
 * **Validates: Requirements 1.1, 7.1, 11.1**
 *
 * ### What "submitted fields" means here
 * `createWallet` / `createCategory` / `createPreset` take already-validated write-side value types
 * ([NewWallet], [CategoryFields], [PresetFields]) whose names/labels are trimmed by
 * [MetadataValidator] before construction. This test therefore generates the value types with
 * already-trimmed names/labels (mirroring how the validator hands them to the repository) and
 * asserts the persisted row matches those values exactly.
 *
 * The one non-identity mapping is the category icon: [com.alx.moneytracker.data.local.entity.CategoryEntity.icon]
 * is a non-null `String`, so [RoomMetadataRepository.createCategory] persists a null
 * [CategoryFields.icon] as the empty string `""` (`icon.orEmpty()`). The assertion checks that
 * actual persisted behavior rather than assuming a null round-trips.
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Wallets_Store` / `Categories_Store` / `Presets_Store`
 * (the Room `wallets`, `categories`, and `quick_presets` tables) reached through
 * [RoomMetadataRepository]. It runs against an actual [AppDatabase] rather than a fake, following
 * the conventions of the existing Scope 1 & 2 Room tests
 * ([RoomHistoryRepositoryReadIsolationTest], [com.alx.moneytracker.data.local.TransactionDaoAtomicityTest]):
 * a real in-memory database built with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries], with suspend calls driven under
 * [runBlocking] on the test thread.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`), so — matching the other Room-backed property tests in
 * this module — inputs are generated with a seeded [kotlin.random.Random] across [ITERATIONS]
 * (>= 100) independent cases. The seeded RNG makes any failure deterministically reproducible from
 * the printed seed.
 *
 * ### Snapshot comparison
 * Each created record is read back directly via raw SQL (bypassing the repository, so the assertion
 * does not depend on the code under test) using `last_insert_rowid()` to locate the freshly
 * inserted row.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryCreatePersistsFieldsTest {

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
        // Direct dispatcher so runBlocking on the test thread observes each write synchronously.
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

    // region raw-SQL read-back helpers (bypass the code under test for independent snapshots)

    private data class WalletRow(
        val name: String,
        val balance: Long,
        val isDefault: Boolean,
        val isArchived: Boolean
    )

    private data class CategoryRow(
        val name: String,
        val type: String,
        val icon: String,
        val isArchived: Boolean
    )

    private data class PresetRow(
        val amount: Long,
        val label: String
    )

    /** The most recently inserted row id in this connection. */
    private fun lastInsertRowId(): Long {
        db.openHelper.writableDatabase.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun readWallet(id: Long): WalletRow? {
        db.openHelper.writableDatabase.query(
            "SELECT name, balance, is_default, is_archived FROM wallets WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return WalletRow(
                name = cursor.getString(0),
                balance = cursor.getLong(1),
                isDefault = cursor.getInt(2) != 0,
                isArchived = cursor.getInt(3) != 0
            )
        }
    }

    private fun readCategory(id: Long): CategoryRow? {
        db.openHelper.writableDatabase.query(
            "SELECT name, type, icon, is_archived FROM categories WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return CategoryRow(
                name = cursor.getString(0),
                type = cursor.getString(1),
                icon = cursor.getString(2),
                isArchived = cursor.getInt(3) != 0
            )
        }
    }

    private fun readPreset(id: Long): PresetRow? {
        db.openHelper.writableDatabase.query(
            "SELECT amount, label FROM quick_presets WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return PresetRow(
                amount = cursor.getLong(0),
                label = cursor.getString(1)
            )
        }
    }

    private fun clearStores() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM wallets")
        db.openHelper.writableDatabase.execSQL("DELETE FROM categories")
        db.openHelper.writableDatabase.execSQL("DELETE FROM quick_presets")
    }

    // endregion

    // region generators

    /**
     * A valid, already-trimmed name/label of length `1..max`. Because the value types the
     * repository consumes have already been trimmed by [MetadataValidator], the generated string
     * must have no leading/trailing whitespace: it is built from a non-space alphabet, and interior
     * spaces are allowed only strictly between two non-space characters.
     */
    private fun validTrimmedName(rng: Random, max: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val length = rng.nextInt(1, max + 1)
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            val interior = i in 1 until length - 1
            // Allow an occasional interior space; never at the first or last position.
            if (interior && rng.nextInt(0, 6) == 0) {
                sb.append(' ')
            } else {
                sb.append(alphabet[rng.nextInt(alphabet.length)])
            }
        }
        return sb.toString()
    }

    /** A balance in `0..MAX_MONEY`, biased to include the boundaries `0` and `MAX_MONEY`. */
    private fun validBalance(rng: Random): Long = when (rng.nextInt(0, 10)) {
        0 -> 0L
        1 -> MetadataValidator.MAX_MONEY
        else -> rng.nextLong(0, MetadataValidator.MAX_MONEY + 1)
    }

    /** An amount in `1..MAX_MONEY`, biased to include the boundaries `1` and `MAX_MONEY`. */
    private fun validAmount(rng: Random): Long = when (rng.nextInt(0, 10)) {
        0 -> 1L
        1 -> MetadataValidator.MAX_MONEY
        else -> rng.nextLong(1, MetadataValidator.MAX_MONEY + 1)
    }

    /** A category type restricted to the permitted values (never TRANSFER). */
    private fun validCategoryType(rng: Random): TransactionType =
        if (rng.nextBoolean()) TransactionType.INCOME else TransactionType.EXPENSE

    /**
     * An optional icon: sometimes null (no icon submitted), sometimes a non-empty identifier, and
     * sometimes the empty string, so the persisted `icon.orEmpty()` mapping is exercised at each.
     */
    private fun optionalIcon(rng: Random): String? = when (rng.nextInt(0, 3)) {
        0 -> null
        1 -> ""
        else -> "icon-${rng.nextInt(0, 1000)}"
    }

    // endregion

    /**
     * Property 10 — a valid creation persists exactly the submitted fields.
     *
     * Runs [ITERATIONS] randomized cases. Each case creates a fresh wallet, category, and preset
     * through the repository from generated (already-trimmed) valid submissions, reads each
     * persisted row back via raw SQL, and asserts every field matches the submission — including
     * `is_default` (first active wallet becomes default) and `is_archived = false` for wallet and
     * category, and the null-icon → empty-string mapping for a category.
     */
    @Test
    fun validCreation_persistsExactlyTheSubmittedFields() = runBlocking {
        val outerSeed = System.nanoTime()
        println("RoomMetadataRepositoryCreatePersistsFieldsTest outerSeed=$outerSeed")
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runCreateCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 10 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStores()
        }
    }

    private fun runCreateCase(rng: Random) = runBlocking {
        // ---- Wallet ----
        val newWallet = NewWallet(
            name = validTrimmedName(rng, MetadataValidator.WALLET_NAME_MAX),
            balance = validBalance(rng)
        )
        val walletResult = repository.createWallet(newWallet)
        assertTrue("createWallet should succeed", walletResult.isSuccess)
        val walletId = lastInsertRowId()
        val walletRow = readWallet(walletId)
            ?: error("created wallet row not found for id $walletId")

        assertEquals(
            "Persisted wallet name must equal the submitted (trimmed) name",
            newWallet.name,
            walletRow.name
        )
        assertEquals(
            "Persisted wallet balance must equal the submitted balance",
            newWallet.balance,
            walletRow.balance
        )
        // First active wallet becomes the default (Requirement 1.5); the store was cleared before
        // this case, so this wallet is the first active one.
        assertEquals(
            "The first active wallet created into an empty store must be the default",
            true,
            walletRow.isDefault
        )
        assertEquals(
            "A newly created wallet must not be archived",
            false,
            walletRow.isArchived
        )

        // ---- Category ----
        val categoryFields = CategoryFields(
            name = validTrimmedName(rng, MetadataValidator.CATEGORY_NAME_MAX),
            type = validCategoryType(rng),
            icon = optionalIcon(rng)
        )
        val categoryResult = repository.createCategory(categoryFields)
        assertTrue("createCategory should succeed", categoryResult.isSuccess)
        val categoryId = lastInsertRowId()
        val categoryRow = readCategory(categoryId)
            ?: error("created category row not found for id $categoryId")

        assertEquals(
            "Persisted category name must equal the submitted (trimmed) name",
            categoryFields.name,
            categoryRow.name
        )
        assertEquals(
            "Persisted category type must equal the submitted type",
            categoryFields.type.name,
            categoryRow.type
        )
        // CategoryEntity.icon is non-null; a null submitted icon is persisted as "" (icon.orEmpty()).
        assertEquals(
            "Persisted category icon must equal the submitted icon, mapping null to empty string",
            categoryFields.icon.orEmpty(),
            categoryRow.icon
        )
        assertEquals(
            "A newly created category must not be archived",
            false,
            categoryRow.isArchived
        )

        // ---- Quick Preset ----
        val presetFields = PresetFields(
            amount = validAmount(rng),
            label = validTrimmedName(rng, MetadataValidator.PRESET_LABEL_MAX)
        )
        val presetResult = repository.createPreset(presetFields)
        assertTrue("createPreset should succeed", presetResult.isSuccess)
        val presetId = lastInsertRowId()
        val presetRow = readPreset(presetId)
            ?: error("created preset row not found for id $presetId")

        assertEquals(
            "Persisted preset amount must equal the submitted amount",
            presetFields.amount,
            presetRow.amount
        )
        assertEquals(
            "Persisted preset label must equal the submitted (trimmed) label",
            presetFields.label,
            presetRow.label
        )
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
