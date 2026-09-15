package com.alx.moneytracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.domain.ValidationResult
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
 * Room in-memory property test for **renaming a wallet changes only its name** (Task 7.7).
 *
 * ```
 * // Feature: customization-metadata, Property 7: Renaming a wallet changes only its name
 * ```
 *
 * *For any* existing Wallet and *any* valid new name, renaming persists the trimmed name on that
 * wallet and leaves its `Balance`, `is_default`, and `is_archived` values unchanged.
 *
 * **Validates: Requirements 3.1, 3.3**
 *
 * ### Why this is an instrumented test
 * The property is stated over the *real* `Wallets_Store` (the Room `wallets` table) through
 * [RoomMetadataRepository.renameWallet], which delegates to the single-row
 * [WalletDao.updateName] `UPDATE`. It must therefore run against an actual Room database rather
 * than a fake. Following the conventions of the sibling Scope 1/2/3 Room tests
 * ([RoomMetadataRepositoryOverridePropertyTest],
 * [com.alx.moneytracker.data.local.TransactionDaoAtomicityTest]), it builds a real [AppDatabase]
 * with [Room.inMemoryDatabaseBuilder] + [androidx.room.RoomDatabase.Builder.allowMainThreadQueries]
 * and runs suspend calls under [runBlocking] on the test thread.
 *
 * ### Randomized generation (no kotest-property under instrumentation)
 * `kotest-property` is only on the unit-test classpath (`testImplementation`), not the instrumented
 * classpath (`androidTestImplementation`). Matching the established Scope 1 & 2 / Scope 3
 * instrumented-PBT convention, this test generates its inputs with a seeded [kotlin.random.Random].
 * It runs [ITERATIONS] (>= 100) independent cases; each case seeds a fresh multi-wallet store,
 * picks a random target wallet, and renames it to a randomly generated **valid** new name.
 * The seeded RNG makes any failure deterministically reproducible from the printed seed.
 *
 * ### The trim contract
 * [RoomMetadataRepository.renameWallet] writes the string it is handed **as-is** — it does not
 * trim. Trimming is the ViewModel's responsibility, performed by
 * [MetadataValidator.validateWalletName] before persistence. This test therefore mirrors that
 * pipeline: it generates a raw candidate name (which may carry surrounding whitespace to exercise
 * trimming), runs it through [MetadataValidator.validateWalletName] to obtain the trimmed value a
 * ViewModel would produce, passes that value to [RoomMetadataRepository.renameWallet], and asserts
 * the persisted name equals the trimmed value. The generated raw name is always valid, so
 * validation always succeeds.
 *
 * ### Snapshot comparison
 * Before and after the rename, the store is snapshotted by reading every `wallets` row directly via
 * raw SQL (bypassing the repository, so the assertion does not depend on the code under test). The
 * snapshot captures id, name, balance, is_default, and is_archived for every wallet.
 */
@RunWith(AndroidJUnit4::class)
class RoomMetadataRepositoryRenamePropertyTest {

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
     * Property 7 — renaming a wallet changes only its name.
     *
     * Runs [ITERATIONS] randomized cases. Each case:
     * 1. seeds a fresh multi-wallet store with random names/balances/flags,
     * 2. snapshots every row/field,
     * 3. renames a randomly chosen wallet to a randomly generated valid (post-trim) name,
     * 4. re-snapshots and asserts the target's name equals the trimmed new name, the target's
     *    balance/is_default/is_archived are unchanged, and every other wallet's row is unchanged.
     */
    @Test
    fun rename_changesOnlyTheName() = runBlocking {
        val outerSeed = System.nanoTime()
        val outerRng = Random(outerSeed)

        repeat(ITERATIONS) { iteration ->
            val caseSeed = outerRng.nextLong()
            try {
                runRenameCase(Random(caseSeed))
            } catch (t: Throwable) {
                throw AssertionError(
                    "Property 7 failed on iteration $iteration " +
                        "(outerSeed=$outerSeed, caseSeed=$caseSeed): ${t.message}",
                    t
                )
            }
            clearStore()
        }
    }

    private fun runRenameCase(rng: Random) = runBlocking {
        // --- Seed a multi-wallet store (1..5 wallets) with random fields. ---
        val walletCount = rng.nextInt(1, 6)
        // Exactly one default among the seeded wallets keeps the store in a realistic state; the
        // rename must not alter default designation regardless.
        val defaultIndex = rng.nextInt(walletCount)
        val ids = (0 until walletCount).map { i ->
            insertWallet(
                name = "Wallet-$i-${rng.nextInt(0, 1000)}",
                balance = randomMoney(rng),
                isDefault = i == defaultIndex,
                isArchived = rng.nextInt(0, 4) == 0 // occasionally archived
            )
        }

        // --- Choose a target wallet and a valid new name (raw, then trimmed via the validator). ---
        val targetId = ids.random(rng)
        val rawName = randomValidRawName(rng)

        // Mirror the ViewModel pipeline: validate (and thereby trim) before persisting. The raw
        // name is generated to always be valid, so validation must succeed.
        val validation = MetadataValidator.validateWalletName(rawName)
        assertTrue(
            "Generated name should be valid per MetadataValidator (raw=\"$rawName\")",
            validation is ValidationResult.Valid
        )
        val trimmedName = (validation as ValidationResult.Valid).value

        // --- Snapshot BEFORE the rename. ---
        val before = snapshotStore()

        // --- Apply the rename with the trimmed name (what a ViewModel would hand the repository). ---
        val result = repository.renameWallet(targetId, trimmedName)
        assertEquals(
            "Rename with a valid name should succeed",
            Result.success(Unit),
            result
        )

        // --- Snapshot AFTER; assert only-the-name semantics. ---
        val after = snapshotStore()

        assertEquals(
            "Rename changed the wallet row count",
            before.size,
            after.size
        )
        assertEquals(
            "Rename changed the set of wallet ids",
            before.keys,
            after.keys
        )

        val beforeTarget = before.getValue(targetId)
        val afterTarget = after.getValue(targetId)

        // 3.1 — the trimmed new name is persisted on the target wallet.
        assertEquals(
            "Target wallet name must equal the trimmed new name exactly",
            trimmedName,
            afterTarget.name
        )
        // 3.3 — the target wallet's balance, is_default, and is_archived are unchanged.
        assertEquals(
            "Rename must not change the target wallet's balance",
            beforeTarget.balance,
            afterTarget.balance
        )
        assertEquals(
            "Rename must not change the target wallet's is_default",
            beforeTarget.isDefault,
            afterTarget.isDefault
        )
        assertEquals(
            "Rename must not change the target wallet's is_archived",
            beforeTarget.isArchived,
            afterTarget.isArchived
        )

        // Every other wallet's row (all fields, including its name) is unchanged.
        for ((id, beforeRow) in before) {
            if (id == targetId) continue
            assertEquals(
                "Rename must not change any other wallet's row (wallet $id)",
                beforeRow,
                after.getValue(id)
            )
        }
    }

    /** A random balance for a seeded wallet, spanning the whole valid range and its boundaries. */
    private fun randomMoney(rng: Random): Long = when (rng.nextInt(0, 10)) {
        0 -> 0L
        1 -> MetadataValidator.MAX_MONEY
        else -> rng.nextLong(0, MetadataValidator.MAX_MONEY + 1)
    }

    /**
     * A random **valid** wallet name whose trimmed length is in `1..WALLET_NAME_MAX`. The core body
     * is drawn from a mix of letters, digits, spaces, and unicode so trimming and interior
     * whitespace are exercised; surrounding whitespace is added on some cases to confirm the
     * validator trims it. The trimmed core is always non-empty and within bound, so the name is
     * always accepted.
     */
    private fun randomValidRawName(rng: Random): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_é漢字"
        val nonSpace = alphabet.filter { !it.isWhitespace() }
        // Core length 1..WALLET_NAME_MAX, guaranteeing a non-whitespace char so the trim is non-empty.
        val coreLen = rng.nextInt(1, MetadataValidator.WALLET_NAME_MAX + 1)
        val core = buildString {
            // Ensure at least one non-space character so the trimmed name is never empty.
            append(nonSpace[rng.nextInt(nonSpace.length)])
            repeat(coreLen - 1) {
                append(alphabet[rng.nextInt(alphabet.length)])
            }
        }.trim().ifEmpty { "W" } // defensive: never empty after trim
        // Optionally wrap with surrounding whitespace to exercise the validator's trimming.
        val leading = " ".repeat(rng.nextInt(0, 3))
        val trailing = " ".repeat(rng.nextInt(0, 3))
        // Keep the trimmed length within bound (the core already is; wrapping is stripped by trim()).
        return "$leading$core$trailing"
    }

    private companion object {
        /** Randomized iteration count, matching the design's "minimum 100 iterations" convention. */
        const val ITERATIONS = 100
    }
}
