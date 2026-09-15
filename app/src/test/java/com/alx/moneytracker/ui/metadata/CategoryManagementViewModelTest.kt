package com.alx.moneytracker.ui.metadata

import com.alx.moneytracker.data.repository.MetadataRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/**
 * Unit tests for [CategoryManagementViewModel] state and event handling (Task 10.3).
 *
 * These are example/edge-case unit tests (not property tests) pinning down the ViewModel's
 * validate-before-persist contract and its state partitioning:
 *  - a TRANSFER [TransactionType] on CreateCategory or UpdateCategory sets
 *    `state.error == TYPE_NOT_PERMITTED` and never reaches the repository (Requirements 7.3, 9.3),
 *  - an invalid (blank / over-long) name on create or update sets the corresponding
 *    [ValidationError] and never reaches the repository,
 *  - active categories from `observeCategories()` are partitioned by [Category.type] into the
 *    `income` and `expense` groups (Requirement 8.1).
 *
 * The ViewModel builds `uiState` with `flowOn(Dispatchers.IO)` and
 * `stateIn(..., WhileSubscribed(5000), Initial)`, so (1) the pipeline only recomputes beyond
 * [CategoryManagementUiState.Initial] while a collector is active — each test launches one and
 * cancels it at the end — and (2) emissions are asynchronous relative to the test thread, so the
 * tests suspend via [awaitState] until the expected state arrives rather than reading `.value`
 * eagerly. Because the pipeline runs on a real dispatcher the tests use [runBlocking] (real time);
 * the Main dispatcher is an [UnconfinedTestDispatcher] so `viewModelScope` work is driven eagerly.
 *
 * A [RecordingMetadataRepository] backed by a [MutableStateFlow] gives the tests control over the
 * observed categories and records every write so a test can assert a rejected submission never
 * touches the repository (the store is left unchanged).
 *
 * Validates: Requirements 7.3, 8.1, 9.3
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagementViewModelTest : FunSpec({

    beforeTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    afterTest {
        Dispatchers.resetMain()
    }

    fun category(
        id: Long,
        name: String,
        type: TransactionType
    ): Category = Category(
        id = id,
        name = name,
        type = type,
        icon = "icon",
        isArchived = false
    )

    test("CreateCategory with TRANSFER sets TYPE_NOT_PERMITTED and does not persist (Req 7.3)") {
        runBlocking {
            val repository = RecordingMetadataRepository()
            val viewModel = CategoryManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.onEvent(
                CategoryManagementEvent.CreateCategory(name = "Salary", type = TransactionType.TRANSFER)
            )

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.TYPE_NOT_PERMITTED
            // A rejected submission never reaches the repository; the store is unchanged.
            repository.createdCategories.shouldContainExactly()

            collector.cancel()
        }
    }

    test("UpdateCategory with TRANSFER sets TYPE_NOT_PERMITTED and does not persist (Req 9.3)") {
        runBlocking {
            val repository = RecordingMetadataRepository()
            val viewModel = CategoryManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.onEvent(
                CategoryManagementEvent.UpdateCategory(categoryId = 5L, type = TransactionType.TRANSFER)
            )

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.TYPE_NOT_PERMITTED
            repository.updatedCategories.shouldContainExactly()

            collector.cancel()
        }
    }

    test("CreateCategory with a blank name sets NAME_EMPTY and does not persist") {
        runBlocking {
            val repository = RecordingMetadataRepository()
            val viewModel = CategoryManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.onEvent(
                CategoryManagementEvent.CreateCategory(name = "   ", type = TransactionType.EXPENSE)
            )

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.NAME_EMPTY
            repository.createdCategories.shouldContainExactly()

            collector.cancel()
        }
    }

    test("UpdateCategory with an over-long name sets NAME_TOO_LONG and does not persist") {
        runBlocking {
            val repository = RecordingMetadataRepository()
            val viewModel = CategoryManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            // 51 chars exceeds CATEGORY_NAME_MAX (50).
            val tooLong = "x".repeat(51)
            viewModel.onEvent(
                CategoryManagementEvent.UpdateCategory(categoryId = 5L, name = tooLong)
            )

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.NAME_TOO_LONG
            repository.updatedCategories.shouldContainExactly()

            collector.cancel()
        }
    }

    test("uiState partitions active categories into income/expense groups (Req 8.1)") {
        runBlocking {
            val income = category(1L, "Salary", TransactionType.INCOME)
            val expenseFood = category(2L, "Food", TransactionType.EXPENSE)
            val expenseRent = category(3L, "Rent", TransactionType.EXPENSE)
            val repository = RecordingMetadataRepository(
                categories = listOf(income, expenseFood, expenseRent)
            )
            val viewModel = CategoryManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            val state = viewModel.uiState.awaitState { it.income.isNotEmpty() || it.expense.isNotEmpty() }
            state.income.shouldContainExactly(income)
            state.expense.shouldContainExactly(expenseFood, expenseRent)

            collector.cancel()
        }
    }
})

/**
 * Suspends until [uiState] emits a [CategoryManagementUiState] satisfying [predicate], returning
 * that state. Bounded by a timeout so a never-satisfied predicate fails fast. Needed because the
 * ViewModel pipeline runs on a real background dispatcher (`flowOn(Dispatchers.IO)`), so state
 * updates are asynchronous relative to the test thread.
 */
private suspend fun StateFlow<CategoryManagementUiState>.awaitState(
    predicate: (CategoryManagementUiState) -> Boolean
): CategoryManagementUiState = withTimeout(5_000) { first(predicate) }

/**
 * File-private recording [MetadataRepository] shared by the category and preset ViewModel tests.
 *
 * The three observation streams are backed by [MutableStateFlow]s the tests seed at construction.
 * Every write records its argument(s) so a test can assert that a validation-rejected submission
 * never reaches the repository (leaving the store unchanged) and returns [Result.success] so a
 * valid submission does not spuriously trip the operation-failed flag. This repository has no
 * transaction-write surface (Requirement 15, enforced structurally by the interface).
 */
private class RecordingMetadataRepository(
    wallets: List<Wallet> = emptyList(),
    categories: List<Category> = emptyList(),
    presets: List<QuickPreset> = emptyList()
) : MetadataRepository {

    private val walletsFlow = MutableStateFlow(wallets)
    private val categoriesFlow = MutableStateFlow(categories)
    private val presetsFlow = MutableStateFlow(presets)

    val createdCategories = mutableListOf<CategoryFields>()
    val updatedCategories = mutableListOf<Pair<Long, CategoryPatch>>()
    val createdPresets = mutableListOf<PresetFields>()
    val updatedPresets = mutableListOf<Pair<Long, PresetPatch>>()
    val deletedPresetIds = mutableListOf<Long>()

    override fun observeWallets(): Flow<List<Wallet>> = walletsFlow

    override fun observeCategories(): Flow<List<Category>> = categoriesFlow

    override fun observeQuickPresets(): Flow<List<QuickPreset>> = presetsFlow

    override suspend fun createWallet(wallet: NewWallet): Result<Unit> = Result.success(Unit)

    override suspend fun renameWallet(walletId: Long, name: String): Result<Unit> = Result.success(Unit)

    override suspend fun overrideBalance(walletId: Long, target: Long): Result<Unit> = Result.success(Unit)

    override suspend fun archiveWallet(walletId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun setDefaultWallet(walletId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun createCategory(fields: CategoryFields): Result<Unit> {
        createdCategories.add(fields)
        return Result.success(Unit)
    }

    override suspend fun updateCategory(categoryId: Long, patch: CategoryPatch): Result<Unit> {
        updatedCategories.add(categoryId to patch)
        return Result.success(Unit)
    }

    override suspend fun archiveCategory(categoryId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun createPreset(fields: PresetFields): Result<Unit> {
        createdPresets.add(fields)
        return Result.success(Unit)
    }

    override suspend fun updatePreset(presetId: Long, patch: PresetPatch): Result<Unit> {
        updatedPresets.add(presetId to patch)
        return Result.success(Unit)
    }

    override suspend fun deletePreset(presetId: Long): Result<Unit> {
        deletedPresetIds.add(presetId)
        return Result.success(Unit)
    }
}
