package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.domain.NewWallet
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
 * Room in-memory property test for the **exactly-one-default invariant** (Task 7.5, Property 4).
 *
 * ```
 * // Feature: customization-metadata, Property 4: Exactly one active wallet is the default whenever an active wallet exists
 * ```
 *
 * *For any* sequence of wallet operations (create, set-default, archive), after each operation the
 * `Wallets_Store` has exactly one `Active_Wallet` with `is_default = true` when at least one
 * `Active_Wallet` exists, and none when no `Active_Wallet` exists; no intermediate state with zero
 * or two-or-more active defaults is ever observable. Consequently: creating a wallet into a store
 * with no `Active_Wallet` makes it the default and creating into a non-empty store does not;
 * designating an `Active_Wallet` sets exactly that wallet's `is_default` to true and clears all
 * others; designating the current default is a no-op; and designating an archived wallet is
 * rejected with the prior default retained (Requirements 1.5, 6.1, 6.2, 6.3, 6.4).
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Wallets_Store` (the Room `wallets` table) and it must
 * hold after each atomic DAO write, so it runs against an actual Room database driven through the
 * implemented [RoomMetadataRepository] rather than a fake. Following the conventions of the
 * existing Scope 1 & 2 Room tests (`TransactionDaoAtomicityTest`,
 * `RoomHistoryRepositoryReadIsolationTest`), it builds a real [AppDatabase] with
 * [Room.inMemoryDatabaseBuilder] + [androidx.room.RoomDatabase.Builder.allowMainThreadQueries] and
 * runs every suspend call under [runBlocking] on the test thread. The repository is constructed
 * with [Dispatchers.Unconfined] so its internal `withContext(ioDispatcher)` executes synchronously
 * on the test thread, and its atomic `@Transaction` DAO methods run against the in-memory DB.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`), so — matching the established convention of
 * `RoomHistoryRepositoryReadIsolationTest` — this test generates its inputs with a seeded
 * [kotlin.random.Random]. It runs [ITERATIONS] (>= 100) independent cases; each case applies a
 * randomly generated sequence of create / set-default / archive operations and re-checks the
 * invariant after every step. The seeded RNG makes any failure deterministically reproducible from
 * the printed seed.
 *
 * ### Invariant checked after every operation (via raw SQL, not the code under test)
 * After each step the test reads the `wallets` table directly and asserts:
 * - the number of active (`is_archived = 0`) wallets with `is_default = 1` is exactly 1 when any
 *   active wallet exists, and 0 when none exists (Requirement 6.2);
 * - no *archived* wallet is ever left flagged as the default (an archived default must have been
 *   reassigned or cleared — Requirements 5.5/5.6 support, 6.4).
 *
 * The specific consequences of the property are exercised inline by the generated operation mix
 * and asserted where they occur:
 * - **create-when-empty / create-into-non-empty** (Requirement 1.5): after a create, the count of
 *   active defaults still equals 1;
 * - **idempotent redesignation** (Requirement 6.3): re-setting the current default leaves the full
 *   wallet snapshot byte-for-byte identical;
 * - **archived-target rejection** (Requirement 6.4): setting an archived wallet as default returns
 *   `Result.failure` and leaves the prior default designation unchanged.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryDefaultInvariantTest {

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
            quickPresetDao = db.quickPresetDao(),
            // Run the repository's suspend writes synchronously on the test thread so the in-memory
            // DB (allowMainThreadQueries) is exercised deterministically under runBlocking.
            ioDispatcher = Dispatchers.Unconfined
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

    /** Reads the entire `wallets` table directly (raw SQL, not the repository) keyed by id. */
    private fun snapshotWallets(): Map<Long, WalletRow> {
        val rows = mutableMapOf<Long, WalletRow>()
        db.openHelper.writableDatabase
            .query("SELECT id, name, balance, is_default, is_archived FROM wallets")
            .use { cursor ->
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

    /** Removes all wallets so the next case starts from a clean, independent state. */
    private fun clearWallets() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM wallets")
    }

    /**
     * Asserts the core invariant against the current store: exactly one active wallet is the
     * default when any active wallet exists, none otherwise, and no archived wallet is left flagged
     * as the default.
     */
    private fun assertExactlyOneDefaultInvariant(context: String) {
        val wallets = snapshotWallets().values
        val active = wallets.filter { !it.isArchived }
        val activeDefaults = active.count { it.isDefault }
        val archivedDefaults = wallets.count { it.isArchived && it.isDefault }

        if (active.isEmpty()) {
            assertEquals(
                "$context: expected no active default when no active wallet exists (Req 6.2)",
                0,
                activeDefaults
            )
        } else {
            assertEquals(
                "$context: expected exactly one active default among ${active.size} active wallets (Req 6.2)",
                1,
                activeDefaults
            )
        }
        assertEquals(
            "$context: no archived wallet may remain flagged as the default (Req 5.5/5.6, 6.4)",
            0,
            archivedDefaults
        )
    }

    /**
     * Property 4 — the exactly-one-default invariant holds after every wallet operation.
     *
     * Runs [ITERATIONS] randomized cases. Each case applies a random sequence of create /
     * set-default / archive operations through the [RoomMetadataRepository], re-checking the
     * invariant after each step and asserting the specific consequences (create resolution,
     * idempotent redesignation, archived-target rejection) where they occur.
     */
    @Test
    fun exactlyOneActiveDefault_holdsAfterEveryWalletOperation() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runInvariantCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 4 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearWallets()
        }
    }

    private fun runInvariantCase(rng: Random) = runBlocking {
        // Invariant holds trivially on the empty store.
        assertExactlyOneDefaultInvariant("empty store")

        // Track the ids ever created so set-default / archive can target existing and, occasionally,
        // non-existent or already-archived wallets to exercise the rejection / no-op paths.
        val createdIds = mutableListOf<Long>()

        val steps = rng.nextInt(1, 25)
        repeat(steps) { step ->
            // Bias toward creating early so later set-default / archive steps have wallets to act on.
            val choice = when {
                createdIds.isEmpty() -> 0
                else -> rng.nextInt(0, 3)
            }

            when (choice) {
                // --- Create a wallet (Requirement 1.5). ---
                0 -> {
                    val activeBefore = activeCount()
                    val name = randomName(rng)
                    val balance = rng.nextLong(0, 1_000_000)
                    val result = repository.createWallet(NewWallet(name = name, balance = balance))
                    assertTrue(
                        "step $step: createWallet should succeed for valid input",
                        result.isSuccess
                    )
                    val newId = latestWalletId()
                    createdIds += newId

                    val created = snapshotWallets().getValue(newId)
                    if (activeBefore == 0) {
                        assertTrue(
                            "step $step: first active wallet must be created as the default (Req 1.5)",
                            created.isDefault
                        )
                    } else {
                        assertTrue(
                            "step $step: a wallet created into a non-empty store must not be default (Req 1.5)",
                            !created.isDefault
                        )
                    }
                    assertTrue(
                        "step $step: a newly created wallet must not be archived (Req 1.1)",
                        !created.isArchived
                    )
                    assertExactlyOneDefaultInvariant("step $step after create")
                }

                // --- Set a wallet as the default (Requirements 6.1, 6.2, 6.3, 6.4). ---
                1 -> {
                    val targetId = pickTarget(rng, createdIds)
                    val snapshotBefore = snapshotWallets()
                    val target = snapshotBefore[targetId]

                    val result = repository.setDefaultWallet(targetId)

                    when {
                        target == null -> {
                            // Non-existent target: rejected, store unchanged.
                            assertTrue(
                                "step $step: setDefault on a missing wallet must fail",
                                result.isFailure
                            )
                            assertEquals(
                                "step $step: a rejected setDefault must leave the store unchanged (Req 6.4)",
                                snapshotBefore,
                                snapshotWallets()
                            )
                        }
                        target.isArchived -> {
                            // Archived target: rejected with prior default retained (Req 6.4).
                            assertTrue(
                                "step $step: setDefault on an archived wallet must fail (Req 6.4)",
                                result.isFailure
                            )
                            assertEquals(
                                "step $step: rejected archived setDefault must retain the prior designation (Req 6.4)",
                                snapshotBefore,
                                snapshotWallets()
                            )
                        }
                        target.isDefault -> {
                            // Idempotent redesignation of the current default is a no-op (Req 6.3).
                            assertTrue(
                                "step $step: setDefault on the current default should succeed",
                                result.isSuccess
                            )
                            assertEquals(
                                "step $step: redesignating the current default must be a byte-for-byte no-op (Req 6.3)",
                                snapshotBefore,
                                snapshotWallets()
                            )
                        }
                        else -> {
                            // Valid new default: exactly that wallet becomes default, all others cleared.
                            assertTrue(
                                "step $step: setDefault on an active wallet should succeed (Req 6.1)",
                                result.isSuccess
                            )
                            val after = snapshotWallets()
                            assertTrue(
                                "step $step: the designated wallet must be the default (Req 6.1)",
                                after.getValue(targetId).isDefault
                            )
                            val otherDefaults = after.values.count { it.id != targetId && it.isDefault }
                            assertEquals(
                                "step $step: designating a wallet must clear every other default (Req 6.1)",
                                0,
                                otherDefaults
                            )
                        }
                    }
                    assertExactlyOneDefaultInvariant("step $step after set-default")
                }

                // --- Archive a wallet (Requirements 5.5, 5.6, 6.2). ---
                2 -> {
                    val targetId = pickTarget(rng, createdIds)
                    val snapshotBefore = snapshotWallets()
                    val target = snapshotBefore[targetId]

                    val result = repository.archiveWallet(targetId)

                    when {
                        target == null -> {
                            // Non-existent target: rejected, store unchanged (Req 5.8).
                            assertTrue(
                                "step $step: archiving a missing wallet must fail (Req 5.8)",
                                result.isFailure
                            )
                            assertEquals(
                                "step $step: a rejected archive must leave the store unchanged (Req 5.8)",
                                snapshotBefore,
                                snapshotWallets()
                            )
                        }
                        target.isArchived -> {
                            // Already archived: no-op (Req 5.7).
                            assertTrue(
                                "step $step: archiving an already-archived wallet should succeed as a no-op (Req 5.7)",
                                result.isSuccess
                            )
                            assertEquals(
                                "step $step: archiving an already-archived wallet must be a no-op (Req 5.7)",
                                snapshotBefore,
                                snapshotWallets()
                            )
                        }
                        else -> {
                            assertTrue(
                                "step $step: archiving an active wallet should succeed (Req 5.1)",
                                result.isSuccess
                            )
                            assertTrue(
                                "step $step: the archived wallet must be flagged is_archived (Req 5.1)",
                                snapshotWallets().getValue(targetId).isArchived
                            )
                        }
                    }
                    assertExactlyOneDefaultInvariant("step $step after archive")
                }
            }
        }
    }

    /**
     * Picks an operation target: usually an already-created wallet, but occasionally a
     * deliberately non-existent id so the missing-target rejection path is exercised too.
     */
    private fun pickTarget(rng: Random, createdIds: List<Long>): Long =
        if (createdIds.isNotEmpty() && rng.nextInt(0, 10) != 0) {
            createdIds.random(rng)
        } else {
            // An id that has not been assigned by autoincrement in this case.
            1_000_000L + rng.nextLong(0, 1_000_000)
        }

    /** Reads the number of active (non-archived) wallets directly. */
    private fun activeCount(): Int {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM wallets WHERE is_archived = 0")
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0)
            }
    }

    /** Returns the most recently generated wallet row id. */
    private fun latestWalletId(): Long {
        db.openHelper.writableDatabase.query("SELECT MAX(id) FROM wallets").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /**
     * Generates a valid wallet name (1..100 chars after trimming). Names are drawn from a small
     * alphabet with mixed case and occasional duplicates so archive-driven default reassignment
     * exercises the case-insensitive, id-tiebreak selection order.
     */
    private fun randomName(rng: Random): String {
        val alphabet = "abcABC "
        val length = rng.nextInt(1, 8)
        val raw = (0 until length).map { alphabet.random(rng) }.joinToString("")
        // Ensure at least one non-whitespace char so the trimmed name is non-empty.
        return if (raw.isBlank()) "w${rng.nextInt(0, 100)}" else raw
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
