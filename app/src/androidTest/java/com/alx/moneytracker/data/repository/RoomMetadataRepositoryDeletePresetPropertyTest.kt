package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **preset deletion isolation** (Task 7.10).
 *
 * ```
 * // Feature: customization-metadata, Property 11: Deleting a preset removes only that preset
 * ```
 *
 * *For any* `Quick_Presets_Store` holding at least one preset, deleting one preset removes exactly
 * that record — the store's row count decreases by one and the deleted id is no longer retrievable —
 * and leaves every other preset's row (id, amount, label) completely unchanged.
 *
 * **Validates: Requirements 14.1, 14.3**
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Quick_Presets_Store` (the Room `quick_presets` table)
 * through [RoomMetadataRepository.deletePreset], which delegates to the hard-delete
 * [QuickPresetDao.deleteById] `DELETE`. It must therefore run against an actual Room database rather
 * than a fake. Following the conventions of the existing Scope 1, 2 & 3 Room tests
 * ([RoomMetadataRepositoryOverridePropertyTest],
 * [com.alx.moneytracker.data.local.TransactionDaoAtomicityTest]), it builds a real [AppDatabase]
 * with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries] and runs suspend calls under
 * [runBlocking] on the test thread.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`). Matching the established Scope 3 convention for
 * Room-backed properties ([RoomMetadataRepositoryOverridePropertyTest]), this test generates its
 * inputs with a seeded [kotlin.random.Random]. It runs [ITERATIONS] (>= 100) independent cases; each
 * case seeds a fresh multi-preset store (1..6 presets, occasionally with duplicate amounts and
 * labels), deletes a randomly chosen preset, and asserts the isolation semantics. The seeded RNG
 * makes any failure deterministically reproducible from the printed seed.
 *
 * ### Snapshot comparison
 * Before and after the delete, the store is snapshotted by reading every `quick_presets` row
 * directly via raw SQL (bypassing the repository, so the assertion does not depend on the code under
 * test). The snapshot captures id, amount, and label for every preset.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryDeletePresetPropertyTest {

    private lateinit var db: AppDatabase
    private lateinit var quickPresetDao: QuickPresetDao
    private lateinit var repository: RoomMetadataRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        quickPresetDao = db.quickPresetDao()
        repository = RoomMetadataRepository(
            walletDao = db.walletDao(),
            categoryDao = db.categoryDao(),
            quickPresetDao = quickPresetDao
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A full snapshot of one `quick_presets` row; equality covers every field. */
    private data class PresetRow(
        val id: Long,
        val amount: Long,
        val label: String
    )

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

    /**
     * Reads the entire `quick_presets` table directly (raw SQL, not the repository) into a stable,
     * order-independent snapshot keyed by id.
     */
    private fun snapshotStore(): Map<Long, PresetRow> {
        val rows = mutableMapOf<Long, PresetRow>()
        db.openHelper.writableDatabase.query(
            "SELECT id, amount, label FROM quick_presets"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                rows[id] = PresetRow(
                    id = id,
                    amount = cursor.getLong(1),
                    label = cursor.getString(2)
                )
            }
        }
        return rows
    }

    /** Removes all presets so the next case starts clean. */
    private fun clearStore() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM quick_presets")
    }

    /**
     * Property 11 — deleting a preset removes only that preset.
     *
     * Runs [ITERATIONS] randomized cases. Each case:
     * 1. seeds a fresh multi-preset store with random amounts/labels,
     * 2. snapshots every row/field,
     * 3. deletes a randomly chosen preset,
     * 4. re-snapshots and asserts the count decreased by exactly one, the deleted id is gone and no
     *    longer retrievable, and every surviving preset's row is byte-for-byte unchanged.
     */
    @Test
    fun deletePreset_removesOnlyThatPreset() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runDeleteCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 11 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runDeleteCase(rng: Random) = runBlocking {
        // --- Seed a multi-preset store (1..6 presets) with random fields. ---
        val presetCount = rng.nextInt(1, 7)
        val ids = (0 until presetCount).map { insertPreset(randomAmount(rng), randomLabel(rng)) }

        // --- Choose a target preset to delete. ---
        val targetId = ids.random(rng)

        // --- Snapshot BEFORE the delete. ---
        val before = snapshotStore()

        // --- Delete the target preset. ---
        val result = repository.deletePreset(targetId)
        assertEquals(
            "Deleting an existing preset should succeed",
            Result.success(Unit),
            result
        )

        // --- Snapshot AFTER; assert deletion isolation. ---
        val after = snapshotStore()

        // 14.1 — the count decreases by exactly one.
        assertEquals(
            "Deleting a preset must reduce the store size by exactly one",
            before.size - 1,
            after.size
        )

        // 14.1 — the deleted id is gone from the store and no longer retrievable via the DAO.
        assertFalse(
            "Deleted preset id must be absent from the store",
            after.containsKey(targetId)
        )
        assertEquals(
            "Deleted preset must no longer be retrievable by id",
            null,
            quickPresetDao.findById(targetId)
        )

        // 14.3 — every other preset survives with its row completely unchanged.
        val expectedSurvivors = before.filterKeys { it != targetId }
        assertEquals(
            "Deletion must leave exactly the other presets behind",
            expectedSurvivors.keys,
            after.keys
        )
        for ((id, beforeRow) in expectedSurvivors) {
            assertTrue(
                "Surviving preset $id must still be present",
                after.containsKey(id)
            )
            assertEquals(
                "Deletion must not change any other preset's row (preset $id)",
                beforeRow,
                after.getValue(id)
            )
        }
    }

    /**
     * A random preset amount in `1..MAX_MONEY`. Presets carry a fixed positive value, and the
     * generator is biased to include the boundary values `1` and `MAX_MONEY`, plus repeated amounts
     * across a case to exercise duplicate-value stores.
     */
    private fun randomAmount(rng: Random): Long = when (rng.nextInt(0, 12)) {
        0 -> 1L
        1 -> MAX_MONEY
        2 -> DUPLICATE_AMOUNT
        else -> rng.nextLong(1, MAX_MONEY + 1)
    }

    /**
     * A random preset label. Occasionally emits a repeated label so cases include duplicate-label
     * presets, confirming deletion isolates by id rather than by matching field values.
     */
    private fun randomLabel(rng: Random): String = when (rng.nextInt(0, 8)) {
        0 -> DUPLICATE_LABEL
        else -> "Preset-${rng.nextInt(0, 100_000)}"
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100

        /** Upper bound on money values, mirroring `MetadataValidator.MAX_MONEY`. */
        const val MAX_MONEY = 999_999_999_999L

        /** A shared amount so a case may seed multiple presets with identical amounts. */
        const val DUPLICATE_AMOUNT = 10_000L

        /** A shared label so a case may seed multiple presets with identical labels. */
        const val DUPLICATE_LABEL = "+10.000"
    }
}
