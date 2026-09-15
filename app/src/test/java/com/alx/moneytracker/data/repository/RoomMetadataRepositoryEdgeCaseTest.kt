package com.alx.moneytracker.data.repository

import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.QuickPresetDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.entity.CategoryEntity
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import com.alx.moneytracker.data.local.entity.WalletEntity
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Task 7.12 — Unit tests for [RoomMetadataRepository] edge cases and DAO-failure mapping.
 *
 * These are plain JVM unit tests (`src/test/`) driven by in-memory **fake** metadata DAOs rather
 * than a Room database, matching the convention established by the sibling repository unit tests
 * (`RepositoryErrorMappingTest.kt`, `RepositoryDeltaPropertyTest.kt`). The fakes here carry the
 * `Edge` prefix and are file-private so they never collide with the fakes in those sibling files
 * within this same package. `UnconfinedTestDispatcher` is injected as the repository's
 * `ioDispatcher` so the `withContext(ioDispatcher)` writes run synchronously and deterministically.
 *
 * Coverage:
 * - Req 1.4 (null initial balance -> 0): see the note on the "create-wallet ... balance 0" test.
 *   `NewWallet.balance` is a **non-null** `Long`, so the repository can never receive a null
 *   balance; the null -> 0L coercion happens in the wallet ViewModel before validation (design;
 *   tasks 9.1/9.2). At this layer we assert that a `NewWallet(balance = 0L)` persists a `0` balance,
 *   which is the value the ViewModel forwards for a "no initial balance" submission.
 * - Req 7.4 (no icon): `CategoryFields.icon` is nullable, but `CategoryEntity.icon` / `Category.icon`
 *   are non-null `String` (reused Scope 1 entities; this feature makes **no schema change**). The
 *   repository maps a null icon to the empty string via `fields.icon.orEmpty()`, and `""` is this
 *   codebase's established "no icon" sentinel for the non-null column. We therefore assert the
 *   persisted icon is `""`, satisfying Req 7.4's intent without changing the schema/nullability.
 * - Req 5.8 (archive a non-existent wallet): `Result.failure` (not found) with the store unchanged.
 * - Req 4.7 / 6.5 / 15.4 (DAO throws): each write path maps a thrown DAO exception to
 *   `Result.failure` (carrying the original cause) with the affected store left in its prior state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomMetadataRepositoryEdgeCaseTest : StringSpec({

    fun repositoryWith(
        walletDao: WalletDao,
        categoryDao: CategoryDao = EdgeRecordingCategoryDao(),
        quickPresetDao: QuickPresetDao = EdgeRecordingQuickPresetDao()
    ): RoomMetadataRepository =
        RoomMetadataRepository(
            walletDao = walletDao,
            categoryDao = categoryDao,
            quickPresetDao = quickPresetDao,
            ioDispatcher = UnconfinedTestDispatcher()
        )

    // region Req 1.4 — create-wallet balance persistence

    // NOTE (Req 1.4): The null-initial-balance -> 0 coercion lives in the wallet ViewModel, not the
    // repository: NewWallet.balance is a non-null Long, so createWallet cannot receive null. Here we
    // assert the repository faithfully persists the 0 balance the ViewModel forwards for a
    // "no initial balance" submission. The ViewModel-side null -> 0 mapping is covered by task 9.2.
    "createWallet persists the submitted balance of 0 (Req 1.4 coercion output)" {
        val walletDao = EdgeRecordingWalletDao()
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.createWallet(NewWallet(name = "Dompet", balance = 0L))

            result.isSuccess shouldBe true
            walletDao.inserted.size shouldBe 1
            walletDao.inserted.single().balance shouldBe 0L
        }
    }

    // endregion

    // region Req 7.4 — create-category with no icon

    "createCategory with no icon (null) persists the empty-string no-icon sentinel (Req 7.4)" {
        val categoryDao = EdgeRecordingCategoryDao()
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), categoryDao = categoryDao)

        runTest {
            val result = repository.createCategory(
                CategoryFields(name = "Makan", type = TransactionType.EXPENSE, icon = null)
            )

            result.isSuccess shouldBe true
            categoryDao.inserted.size shouldBe 1
            // "" is the codebase's non-null "no icon" representation (CategoryEntity.icon is a
            // non-null String; the repository maps a null CategoryFields.icon via `.orEmpty()`).
            categoryDao.inserted.single().icon shouldBe ""
        }
    }

    // endregion

    // region Req 5.8 — archive a non-existent wallet

    "archiveWallet on a non-existent wallet returns not-found failure with the store unchanged (Req 5.8)" {
        val existing = WalletEntity(id = 1L, name = "Dompet", balance = 100L, isDefault = true)
        val walletDao = EdgeRecordingWalletDao(initial = listOf(existing))
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.archiveWallet(walletId = 999L)

            result.isFailure shouldBe true
            result.exceptionOrNull().shouldBeInstanceOf<NoSuchElementException>()
            // The Wallets_Store is left exactly as it was: no archive flag flipped, nothing removed.
            walletDao.rows shouldBe listOf(existing)
            walletDao.markArchivedCalls shouldBe 0
        }
    }

    // endregion

    // region Req 4.7 / 6.5 / 15.4 — thrown DAO exception maps to Result.failure per write path

    "createWallet maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("insert failed")
        val walletDao = EdgeThrowingWalletDao(boom)
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.createWallet(NewWallet(name = "Dompet", balance = 10L))

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
            walletDao.rows.shouldBeEmpty()
        }
    }

    "renameWallet maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("update failed")
        val walletDao = EdgeThrowingWalletDao(boom)
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.renameWallet(walletId = 1L, name = "Baru")

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "overrideBalance maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("override failed")
        val walletDao = EdgeThrowingWalletDao(boom)
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.overrideBalance(walletId = 1L, target = 500L)

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "setDefaultWallet maps a thrown DAO exception to Result.failure with the store retained (Req 6.5)" {
        val boom = RuntimeException("assignDefault failed")
        // A findById that throws exercises the failure path before any default reassignment.
        val walletDao = EdgeThrowingWalletDao(boom)
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.setDefaultWallet(walletId = 1L)

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "archiveWallet maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("findById failed")
        val walletDao = EdgeThrowingWalletDao(boom)
        val repository = repositoryWith(walletDao)

        runTest {
            val result = repository.archiveWallet(walletId = 1L)

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "createCategory maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("category insert failed")
        val categoryDao = EdgeThrowingCategoryDao(boom)
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), categoryDao = categoryDao)

        runTest {
            val result = repository.createCategory(
                CategoryFields(name = "Makan", type = TransactionType.EXPENSE, icon = "food")
            )

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "updateCategory maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("category find failed")
        val categoryDao = EdgeThrowingCategoryDao(boom)
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), categoryDao = categoryDao)

        runTest {
            val result = repository.updateCategory(categoryId = 1L, patch = CategoryPatch(name = "Baru"))

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "archiveCategory maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("category find failed")
        val categoryDao = EdgeThrowingCategoryDao(boom)
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), categoryDao = categoryDao)

        runTest {
            val result = repository.archiveCategory(categoryId = 1L)

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "createPreset maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("preset insert failed")
        val quickPresetDao = EdgeThrowingQuickPresetDao(boom)
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), quickPresetDao = quickPresetDao)

        runTest {
            val result = repository.createPreset(PresetFields(amount = 50_000L, label = "Kopi"))

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "updatePreset maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("preset find failed")
        val quickPresetDao = EdgeThrowingQuickPresetDao(boom)
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), quickPresetDao = quickPresetDao)

        runTest {
            val result = repository.updatePreset(presetId = 1L, patch = PresetPatch(amount = 60_000L))

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    "deletePreset maps a thrown DAO exception to Result.failure with the store retained (Req 4.7, 15.4)" {
        val boom = RuntimeException("preset delete failed")
        val quickPresetDao = EdgeThrowingQuickPresetDao(boom)
        val repository = repositoryWith(walletDao = EdgeRecordingWalletDao(), quickPresetDao = quickPresetDao)

        runTest {
            val result = repository.deletePreset(presetId = 1L)

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldBe boom
        }
    }

    // endregion
})

// region Fake DAOs — recording (happy-path) variants

/**
 * In-memory [WalletDao] that records inserts and applies archive/default mutations to a row list,
 * so a test can assert the store is (or is not) mutated. `assignDefault` and
 * `archiveAndReassignDefault` are inherited from the interface (default `@Transaction` bodies).
 */
private class EdgeRecordingWalletDao(initial: List<WalletEntity> = emptyList()) : WalletDao {
    val rows: MutableList<WalletEntity> = initial.toMutableList()
    val inserted: MutableList<WalletEntity> = mutableListOf()
    var markArchivedCalls: Int = 0
        private set

    override fun observeActiveWallets(): Flow<List<WalletEntity>> =
        flowOf(rows.filter { !it.isArchived })

    override fun observeDefaultWallet(): Flow<WalletEntity?> =
        flowOf(rows.firstOrNull { it.isDefault })

    override suspend fun insert(wallet: WalletEntity): Long {
        val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1L
        val stored = wallet.copy(id = id)
        rows.add(stored)
        inserted.add(stored)
        return id
    }

    override suspend fun updateName(id: Long, name: String) {
        rows.replaceAll { if (it.id == id) it.copy(name = name) else it }
    }

    override suspend fun overrideBalance(id: Long, balance: Long) {
        rows.replaceAll { if (it.id == id) it.copy(balance = balance) else it }
    }

    override suspend fun markArchived(id: Long) {
        markArchivedCalls++
        rows.replaceAll { if (it.id == id) it.copy(isArchived = true) else it }
    }

    override suspend fun activeCount(): Int = rows.count { !it.isArchived }

    override suspend fun findById(id: Long): WalletEntity? = rows.firstOrNull { it.id == id }

    override suspend fun clearAllDefaults() {
        rows.replaceAll { it.copy(isDefault = false) }
    }

    override suspend fun setDefault(id: Long) {
        rows.replaceAll { if (it.id == id) it.copy(isDefault = true) else it }
    }
}

/** In-memory [CategoryDao] that records inserts so a test can inspect the persisted entity. */
private class EdgeRecordingCategoryDao : CategoryDao {
    val inserted: MutableList<CategoryEntity> = mutableListOf()

    override fun observeByType(type: String): Flow<List<CategoryEntity>> = flowOf(emptyList())
    override fun observeAllActive(): Flow<List<CategoryEntity>> = flowOf(emptyList())

    override suspend fun insert(category: CategoryEntity): Long {
        val id = (inserted.maxOfOrNull { it.id } ?: 0L) + 1L
        inserted.add(category.copy(id = id))
        return id
    }

    override suspend fun update(category: CategoryEntity) {
        inserted.replaceAll { if (it.id == category.id) category else it }
    }

    override suspend fun findById(id: Long): CategoryEntity? = inserted.firstOrNull { it.id == id }

    override suspend fun markArchived(id: Long) {
        inserted.replaceAll { if (it.id == id) it.copy(isArchived = true) else it }
    }
}

/** In-memory [QuickPresetDao] used where preset writes must succeed (no throwing needed). */
private class EdgeRecordingQuickPresetDao : QuickPresetDao {
    val inserted: MutableList<QuickPresetEntity> = mutableListOf()

    override fun observeAll(): Flow<List<QuickPresetEntity>> = flowOf(emptyList())

    override suspend fun insert(preset: QuickPresetEntity): Long {
        val id = (inserted.maxOfOrNull { it.id } ?: 0L) + 1L
        inserted.add(preset.copy(id = id))
        return id
    }

    override suspend fun update(preset: QuickPresetEntity) {
        inserted.replaceAll { if (it.id == preset.id) preset else it }
    }

    override suspend fun deleteById(id: Long) {
        inserted.removeAll { it.id == id }
    }

    override suspend fun findById(id: Long): QuickPresetEntity? = inserted.firstOrNull { it.id == id }
}

// endregion

// region Fake DAOs — throwing (failure-path) variants

/**
 * [WalletDao] whose every write/read entry point throws [error], so a repository write surfaces
 * `Result.failure`. `rows` stays empty to represent an untouched store on the create path.
 */
private class EdgeThrowingWalletDao(private val error: Throwable) : WalletDao {
    val rows: List<WalletEntity> = emptyList()

    override fun observeActiveWallets(): Flow<List<WalletEntity>> = flowOf(emptyList())
    override fun observeDefaultWallet(): Flow<WalletEntity?> = flowOf(null)

    override suspend fun insert(wallet: WalletEntity): Long = throw error
    override suspend fun updateName(id: Long, name: String): Unit = throw error
    override suspend fun overrideBalance(id: Long, balance: Long): Unit = throw error
    override suspend fun markArchived(id: Long): Unit = throw error
    override suspend fun activeCount(): Int = throw error
    override suspend fun findById(id: Long): WalletEntity? = throw error
    override suspend fun clearAllDefaults(): Unit = throw error
    override suspend fun setDefault(id: Long): Unit = throw error
}

/** [CategoryDao] whose every write/read entry point throws [error]. */
private class EdgeThrowingCategoryDao(private val error: Throwable) : CategoryDao {
    override fun observeByType(type: String): Flow<List<CategoryEntity>> = flowOf(emptyList())
    override fun observeAllActive(): Flow<List<CategoryEntity>> = flowOf(emptyList())

    override suspend fun insert(category: CategoryEntity): Long = throw error
    override suspend fun update(category: CategoryEntity): Unit = throw error
    override suspend fun findById(id: Long): CategoryEntity? = throw error
    override suspend fun markArchived(id: Long): Unit = throw error
}

/** [QuickPresetDao] whose every write/read entry point throws [error]. */
private class EdgeThrowingQuickPresetDao(private val error: Throwable) : QuickPresetDao {
    override fun observeAll(): Flow<List<QuickPresetEntity>> = flowOf(emptyList())

    override suspend fun insert(preset: QuickPresetEntity): Long = throw error
    override suspend fun update(preset: QuickPresetEntity): Unit = throw error
    override suspend fun deleteById(id: Long): Unit = throw error
    override suspend fun findById(id: Long): QuickPresetEntity? = throw error
}

// endregion
