package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.logic.MetadataValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **Property 9** (Task 7.8).
 *
 * ```
 * // Feature: customization-metadata, Property 9: A partial update changes only the submitted fields
 * ```
 *
 * *For any* Category or Quick_Preset and *any* partial update whose omitted fields are represented
 * as null, applying the update through [RoomMetadataRepository.updateCategory] /
 * [RoomMetadataRepository.updatePreset] persists exactly the submitted (already validated, trimmed)
 * fields and leaves every omitted field unchanged. For a Category this explicitly includes the
 * `is_archived` value, which is never a patch field and must survive an update untouched.
 *
 * **Validates: Requirements 9.1, 9.4, 13.1**
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Categories_Store` / `Presets_Store` (the Room
 * `categories` and `quick_presets` tables) through the repository, which reads the existing row via
 * the DAO's `findById`, overlays the non-null patch fields, and writes it back via `@Update`. It
 * therefore runs against an actual [AppDatabase] rather than a fake, following the conventions of
 * the existing Scope 1 & 2 Room tests (`TransactionDaoAtomicityTest`,
 * `RoomHistoryRepositoryReadIsolationTest`) and the sibling metadata property tests: a real
 * in-memory database built with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries], with suspend calls driven under
 * [runBlocking] on the test thread.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`). Matching the established Scope 3 convention for
 * Room-backed properties, this test generates its inputs with a seeded [kotlin.random.Random] and
 * runs [ITERATIONS] (>= 100) independent cases. Each case seeds a fresh category and preset with
 * random fields (the category is seeded archived roughly half the time so that the preservation of
 * `is_archived` is exercised in both states), builds a random partial patch in which each field is
 * independently either omitted (null) or a fresh valid, pre-trimmed value, applies it, and asserts
 * the partial-update characterization. The seeded RNG makes any failure deterministically
 * reproducible from the printed seed embedded in the assertion message.
 *
 * ### Note on patch values
 * The repository applies patch fields directly; per the design, patch names/labels arrive
 * pre-trimmed and validated (trimming/validation is the `MetadataValidator`'s responsibility, tested
 * separately by Property 8). This test therefore generates only already-trimmed valid patch values,
 * so "the submitted field is persisted verbatim" is the correct expectation here.
 *
 * ### Snapshot comparison
 * Before and after the update, the target row is read directly via raw SQL (bypassing the
 * repository), so the assertion does not depend on the code under test.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryPartialUpdatePropertyTest {

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
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // region raw-SQL snapshots (bypass the code under test)

    /** A full snapshot of one `categories` row; equality covers every field. */
    private data class CategoryRow(
        val id: Long,
        val name: String,
        val type: String,
        val icon: String,
        val isArchived: Boolean
    )

    /** A full snapshot of one `quick_presets` row; equality covers every field. */
    private data class PresetRow(
        val id: Long,
        val amount: Long,
        val label: String
    )

    /** Inserts a category directly via raw SQL and returns its generated row id. */
    private fun insertCategory(name: String, type: TransactionType, icon: String, isArchived: Boolean): Long {
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

    /** Inserts a preset directly via raw SQL and returns its generated row id. */
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

    /** Reads a single `categories` row directly, or null when absent. */
    private fun readCategory(id: Long): CategoryRow? {
        db.openHelper.writableDatabase.query(
            "SELECT id, name, type, icon, is_archived FROM categories WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return CategoryRow(
                id = cursor.getLong(0),
                name = cursor.getString(1),
                type = cursor.getString(2),
                icon = cursor.getString(3),
                isArchived = cursor.getInt(4) != 0
            )
        }
    }

    /** Reads a single `quick_presets` row directly, or null when absent. */
    private fun readPreset(id: Long): PresetRow? {
        db.openHelper.writableDatabase.query(
            "SELECT id, amount, label FROM quick_presets WHERE id = ?",
            arrayOf<Any>(id)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return PresetRow(
                id = cursor.getLong(0),
                amount = cursor.getLong(1),
                label = cursor.getString(2)
            )
        }
    }

    private fun clearStore() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM categories")
        db.openHelper.writableDatabase.execSQL("DELETE FROM quick_presets")
    }

    // endregion

    /**
     * Property 9 — a partial update changes only the submitted fields.
     *
     * Runs [ITERATIONS] randomized cases. Each case seeds one category and one preset with random
     * fields, builds a random partial patch for each (each field independently omitted or a fresh
     * valid value), applies the update, and asserts that every submitted field took the new value
     * verbatim while every omitted field — including the category's `is_archived` — is unchanged.
     */
    @Test
    fun partialUpdate_changesOnlySubmittedFields() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runCategoryCase(Random(caseSeed))
                runPresetCase(Random(caseSeed xor 0x5DEECE66DL))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 9 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runCategoryCase(rng: Random) = runBlocking {
        // --- Seed a category. type is INCOME or EXPENSE (never TRANSFER); is_archived random so
        // its preservation is exercised in both the archived and non-archived states. ---
        val originalType = if (rng.nextBoolean()) TransactionType.INCOME else TransactionType.EXPENSE
        val originalArchived = rng.nextBoolean()
        val id = insertCategory(
            name = "Cat-${rng.nextInt(0, 10_000)}",
            type = originalType,
            icon = if (rng.nextBoolean()) "icon-${rng.nextInt(0, 100)}" else "",
            isArchived = originalArchived
        )

        val before = readCategory(id) ?: error("seeded category missing before update")

        // --- Build a random partial patch: each field is independently omitted (null) or a fresh
        // valid, pre-trimmed value. At least one field is submitted so the update is meaningful. ---
        val patchName: String? = maybe(rng) { "Renamed-${rng.nextInt(0, 10_000)}" }
        // A submitted type is INCOME or EXPENSE only (TRANSFER is rejected by validation and never
        // reaches a patch); use the opposite of the original to guarantee an observable change.
        val patchType: TransactionType? = maybe(rng) {
            if (originalType == TransactionType.INCOME) TransactionType.EXPENSE else TransactionType.INCOME
        }
        val patchIcon: String? = maybe(rng) { "newicon-${rng.nextInt(0, 100)}" }

        // Guarantee at least one submitted field.
        val (fName, fType, fIcon) = ensureAtLeastOneCategoryField(rng, patchName, patchType, patchIcon)

        val patch = CategoryPatch(name = fName, type = fType, icon = fIcon)
        val result = repository.updateCategory(id, patch)
        assertTrue("updateCategory should succeed: ${result.exceptionOrNull()}", result.isSuccess)

        val after = readCategory(id) ?: error("category missing after update")

        // Submitted fields take the new value verbatim (9.1); omitted fields are unchanged (9.1).
        assertEquals(
            "Category name should equal the submitted name when provided, else be unchanged",
            fName ?: before.name,
            after.name
        )
        assertEquals(
            "Category type should equal the submitted type when provided, else be unchanged",
            (fType?.name) ?: before.type,
            after.type
        )
        assertEquals(
            "Category icon should equal the submitted icon when provided, else be unchanged",
            fIcon ?: before.icon,
            after.icon
        )
        // is_archived is never a patch field and must be preserved across the update (9.4).
        assertEquals(
            "Category is_archived must be unchanged by a partial update",
            before.isArchived,
            after.isArchived
        )
        // The identity (id) is stable.
        assertEquals("Category id must be unchanged", before.id, after.id)
    }

    private fun runPresetCase(rng: Random) = runBlocking {
        // --- Seed a preset with a random valid amount and label. ---
        val id = insertPreset(
            amount = randomAmount(rng),
            label = "Preset-${rng.nextInt(0, 10_000)}"
        )

        val before = readPreset(id) ?: error("seeded preset missing before update")

        // --- Build a random partial patch: each field independently omitted or a fresh valid value.
        val patchAmount: Long? = maybe(rng) { randomAmount(rng) }
        val patchLabel: String? = maybe(rng) { "NewLabel-${rng.nextInt(0, 10_000)}" }

        val (fAmount, fLabel) = ensureAtLeastOnePresetField(rng, patchAmount, patchLabel)

        val patch = PresetPatch(amount = fAmount, label = fLabel)
        val result = repository.updatePreset(id, patch)
        assertTrue("updatePreset should succeed: ${result.exceptionOrNull()}", result.isSuccess)

        val after = readPreset(id) ?: error("preset missing after update")

        // Submitted fields take the new value verbatim; omitted fields are unchanged (13.1).
        assertEquals(
            "Preset amount should equal the submitted amount when provided, else be unchanged",
            fAmount ?: before.amount,
            after.amount
        )
        assertEquals(
            "Preset label should equal the submitted label when provided, else be unchanged",
            fLabel ?: before.label,
            after.label
        )
        assertEquals("Preset id must be unchanged", before.id, after.id)
    }

    // region generation helpers

    /** Returns [produce]'s value roughly half the time, null otherwise (an "omitted" patch field). */
    private inline fun <T> maybe(rng: Random, produce: () -> T): T? =
        if (rng.nextBoolean()) produce() else null

    /**
     * Ensures at least one category patch field is non-null; if all three came out null, forces the
     * name to a fresh value so the update is meaningful.
     */
    private fun ensureAtLeastOneCategoryField(
        rng: Random,
        name: String?,
        type: TransactionType?,
        icon: String?
    ): Triple<String?, TransactionType?, String?> =
        if (name == null && type == null && icon == null) {
            Triple("Renamed-${rng.nextInt(0, 10_000)}", null, null)
        } else {
            Triple(name, type, icon)
        }

    /**
     * Ensures at least one preset patch field is non-null; if both came out null, forces the label
     * to a fresh value so the update is meaningful.
     */
    private fun ensureAtLeastOnePresetField(
        rng: Random,
        amount: Long?,
        label: String?
    ): Pair<Long?, String?> =
        if (amount == null && label == null) {
            Pair(null, "NewLabel-${rng.nextInt(0, 10_000)}")
        } else {
            Pair(amount, label)
        }

    /** A valid preset amount in `1..MAX_MONEY`, biased to include the boundary values. */
    private fun randomAmount(rng: Random): Long = when (rng.nextInt(0, 10)) {
        0 -> 1L
        1 -> MetadataValidator.MAX_MONEY
        else -> rng.nextLong(1, MetadataValidator.MAX_MONEY + 1)
    }

    // endregion

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
