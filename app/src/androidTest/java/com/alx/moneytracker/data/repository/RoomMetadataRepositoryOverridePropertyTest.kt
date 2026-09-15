package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.domain.logic.MetadataValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Room in-memory property test for **Direct Balance Override overwrite semantics** (Task 7.6).
 *
 * ```
 * // Feature: customization-metadata, Property 6: Direct Balance Override overwrites only the target balance
 * ```
 *
 * *For any* `Wallets_Store` and *any* target balance in `0..MAX_MONEY`, applying a
 * `Direct_Balance_Override` to a wallet sets that wallet's `Balance` exactly to the target value
 * (an overwrite, not a delta), leaves that wallet's name, `is_default`, and `is_archived` values
 * unchanged, and leaves the `Balance` of every other wallet unchanged.
 *
 * **Validates: Requirements 4.1, 4.5, 4.6**
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Wallets_Store` (the Room `wallets` table) through
 * [RoomMetadataRepository.overrideBalance], which delegates to the single-row
 * [WalletDao.overrideBalance] `UPDATE`. It must therefore run against an actual Room database
 * rather than a fake. Following the conventions of the existing Scope 1 & 2 Room tests
 * ([RoomHistoryRepositoryReadIsolationTest], [com.alx.moneytracker.data.local.TransactionDaoAtomicityTest]),
 * it builds a real [AppDatabase] with [Room.inMemoryDatabaseBuilder] +
 * [androidx.room.RoomDatabase.Builder.allowMainThreadQueries] and runs suspend calls under
 * [runBlocking] on the test thread.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`). Matching the established Scope 1 & 2 convention for
 * Room-backed properties, this test generates its inputs with a seeded [kotlin.random.Random]. It
 * runs [ITERATIONS] (>= 100) independent cases; each case seeds a fresh multi-wallet store, picks a
 * random target wallet and a random target balance in `0..MAX_MONEY` (including the boundary values
 * `0` and `MAX_MONEY`, and values equal to the wallet's existing balance to exercise the no-change
 * case), applies the override, and asserts the overwrite semantics. The seeded RNG makes any
 * failure deterministically reproducible from the printed seed.
 *
 * ### Snapshot comparison
 * Before and after the override, the store is snapshotted by reading every `wallets` row directly
 * via raw SQL (bypassing the repository, so the assertion does not depend on the code under test).
 * The snapshot captures id, name, balance, is_default, and is_archived for every wallet.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryOverridePropertyTest {

    private lateinit var db: AppDatabase
    private lateinit var walletDao: WalletDao
    private lateinit var repository: RoomMetadataRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walletDao = db.walletDao()
        repository = RoomMetadataRepository(
            walletDao = walletDao,
            categoryDao = db.categoryDao(),
            quickPresetDao = db.quickPresetDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** A full snapshot of one `wallets` row; equality covers every field. */
    private data class WalletRow(
        val id: Long,
        val name: String,
        val balance: Long,
        val isDefault: Boolean,
        val isArchived: Boolean
    )

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

    /**
     * Reads the entire `wallets` table directly (raw SQL, not the repository) into a stable,
     * order-independent snapshot keyed by id.
     */
    private fun snapshotStore(): Map<Long, WalletRow> {
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
                    isDefault = cursor.getInt(3) != 0,
                    isArchived = cursor.getInt(4) != 0
                )
            }
        }
        return rows
    }

    /** Removes all wallets so the next case starts clean. */
    private fun clearStore() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM wallets")
    }

    /**
     * Property 6 — a Direct Balance Override overwrites only the target wallet's balance.
     *
     * Runs [ITERATIONS] randomized cases. Each case:
     * 1. seeds a fresh multi-wallet store with random names/balances/flags,
     * 2. snapshots every row/field,
     * 3. overrides a randomly chosen wallet's balance to a random target in `0..MAX_MONEY`,
     * 4. re-snapshots and asserts the target's balance equals the target exactly, the target's
     *    name/is_default/is_archived are unchanged, and every other wallet's row is unchanged.
     */
    @Test
    fun override_overwritesOnlyTheTargetBalance() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runOverrideCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 6 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runOverrideCase(rng: Random) = runBlocking {
        // --- Seed a multi-wallet store (1..5 wallets) with random fields. ---
        val walletCount = rng.nextInt(1, 6)
        // Exactly one default among the seeded wallets keeps the store in a realistic state; the
        // override must not alter default designation regardless.
        val defaultIndex = rng.nextInt(walletCount)
        val ids = (0 until walletCount).map { i ->
            insertWallet(
                name = "Wallet-$i-${rng.nextInt(0, 1000)}",
                balance = randomMoney(rng),
                isDefault = i == defaultIndex,
                isArchived = rng.nextInt(0, 4) == 0 // occasionally archived
            )
        }

        // --- Choose a target wallet and a target balance in 0..MAX_MONEY. ---
        val targetId = ids.random(rng)
        val target = randomTargetBalance(rng)

        // --- Snapshot BEFORE the override. ---
        val before = snapshotStore()

        // --- Apply the Direct Balance Override. ---
        val result = repository.overrideBalance(targetId, target)
        assertEquals(
            "Override of an in-range target should succeed",
            Result.success(Unit),
            result
        )

        // --- Snapshot AFTER; assert overwrite semantics. ---
        val after = snapshotStore()

        assertEquals(
            "Override changed the wallet row count",
            before.size,
            after.size
        )
        assertEquals(
            "Override changed the set of wallet ids",
            before.keys,
            after.keys
        )

        val beforeTarget = before.getValue(targetId)
        val afterTarget = after.getValue(targetId)

        // 4.1 — the target balance is set exactly to the submitted value (overwrite, not delta).
        assertEquals(
            "Target wallet balance must equal the override target exactly",
            target,
            afterTarget.balance
        )
        // 4.5 — the target wallet's name, is_default, and is_archived are unchanged.
        assertEquals(
            "Override must not change the target wallet's name",
            beforeTarget.name,
            afterTarget.name
        )
        assertEquals(
            "Override must not change the target wallet's is_default",
            beforeTarget.isDefault,
            afterTarget.isDefault
        )
        assertEquals(
            "Override must not change the target wallet's is_archived",
            beforeTarget.isArchived,
            afterTarget.isArchived
        )

        // 4.6 — every other wallet's row (including balance) is unchanged.
        for ((id, beforeRow) in before) {
            if (id == targetId) continue
            assertEquals(
                "Override must not change any other wallet's row (wallet $id)",
                beforeRow,
                after.getValue(id)
            )
        }
    }

    /** A random balance for a seeded wallet, spanning the whole valid range and its boundaries. */
    private fun randomMoney(rng: Random): Long = randomTargetBalance(rng)

    /**
     * A random target balance in `0..MAX_MONEY`, biased to include the boundary values `0` and
     * `MAX_MONEY` so the overwrite is exercised at the extremes as well as the interior.
     */
    private fun randomTargetBalance(rng: Random): Long = when (rng.nextInt(0, 10)) {
        0 -> 0L
        1 -> MetadataValidator.MAX_MONEY
        else -> rng.nextLong(0, MetadataValidator.MAX_MONEY + 1)
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
