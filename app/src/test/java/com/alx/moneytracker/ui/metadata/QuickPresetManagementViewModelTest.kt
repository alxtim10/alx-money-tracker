package com.alx.moneytracker.ui.metadata

import com.alx.moneytracker.data.repository.MetadataRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.domain.logic.MetadataValidator
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
 * Unit tests for [QuickPresetManagementViewModel] state and event handling (Task 10.3).
 *
 * These are example/edge-case unit tests (not property tests) pinning down the ViewModel's
 * validate-before-persist contract:
 *  - an invalid label (blank / over-long) on CreatePreset sets the corresponding [ValidationError]
 *    and never reaches the repository (Requirement 11.3),
 *  - an invalid amount (out of `1..MAX_MONEY`) on CreatePreset or UpdatePreset sets
 *    [ValidationError.AMOUNT_OUT_OF_RANGE] and never reaches the repository (Requirement 13.2),
 *  - the observed presets from `observeQuickPresets()` surface in `state.presets`.
 *
 * The harness mirrors [CategoryManagementViewModelTest]: the ViewModel builds `uiState` with
 * `flowOn(Dispatchers.IO)` and `stateIn(..., WhileSubscribed(5000), Initial)`, so each test launches
 * a collector (cancelled at the end) and suspends via [awaitState] until the expected state arrives.
 * The tests use [runBlocking] (real time) because the pipeline runs on a real dispatcher, with the
 * Main dispatcher set to an [UnconfinedTestDispatcher] so `viewModelScope` work is driven eagerly.
 *
 * Validates: Requirements 11.3, 13.2
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuickPresetManagementViewModelTest : FunSpec({

    beforeTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("CreatePreset with a blank label sets LABEL_EMPTY and does not persist (Req 11.3)") {
        runBlocking {
            val repository = PresetRecordingRepository()
            val viewModel = QuickPresetManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.onEvent(QuickPresetManagementEvent.CreatePreset(amount = 10_000L, label = "  "))

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.LABEL_EMPTY
            repository.createdPresets.shouldContainExactly()

            collector.cancel()
        }
    }

    test("CreatePreset with an over-long label sets LABEL_TOO_LONG and does not persist") {
        runBlocking {
            val repository = PresetRecordingRepository()
            val viewModel = QuickPresetManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            // 51 chars exceeds PRESET_LABEL_MAX (50).
            val tooLong = "x".repeat(51)
            viewModel.onEvent(QuickPresetManagementEvent.CreatePreset(amount = 10_000L, label = tooLong))

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.LABEL_TOO_LONG
            repository.createdPresets.shouldContainExactly()

            collector.cancel()
        }
    }

    test("CreatePreset with a zero amount sets AMOUNT_OUT_OF_RANGE and does not persist (Req 13.2)") {
        runBlocking {
            val repository = PresetRecordingRepository()
            val viewModel = QuickPresetManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            // 0 is below the inclusive lower bound of 1.
            viewModel.onEvent(QuickPresetManagementEvent.CreatePreset(amount = 0L, label = "+10k"))

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.AMOUNT_OUT_OF_RANGE
            repository.createdPresets.shouldContainExactly()

            collector.cancel()
        }
    }

    test("UpdatePreset with an over-max amount sets AMOUNT_OUT_OF_RANGE and does not persist (Req 13.2)") {
        runBlocking {
            val repository = PresetRecordingRepository()
            val viewModel = QuickPresetManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.onEvent(
                QuickPresetManagementEvent.UpdatePreset(
                    presetId = 3L,
                    amount = MetadataValidator.MAX_MONEY + 1
                )
            )

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.AMOUNT_OUT_OF_RANGE
            repository.updatedPresets.shouldContainExactly()

            collector.cancel()
        }
    }

    test("uiState surfaces the observed presets") {
        runBlocking {
            val presets = listOf(
                QuickPreset(id = 1L, amount = 10_000L, label = "+10k"),
                QuickPreset(id = 2L, amount = 50_000L, label = "+50k")
            )
            val repository = PresetRecordingRepository(presets = presets)
            val viewModel = QuickPresetManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            val state = viewModel.uiState.awaitState { it.presets.isNotEmpty() }
            state.presets.shouldContainExactly(presets)

            collector.cancel()
        }
    }
})

/**
 * Suspends until [uiState] emits a [QuickPresetManagementUiState] satisfying [predicate], returning
 * that state. Bounded by a timeout so a never-satisfied predicate fails fast. Needed because the
 * ViewModel pipeline runs on a real background dispatcher (`flowOn(Dispatchers.IO)`).
 */
private suspend fun StateFlow<QuickPresetManagementUiState>.awaitState(
    predicate: (QuickPresetManagementUiState) -> Boolean
): QuickPresetManagementUiState = withTimeout(5_000) { first(predicate) }

/**
 * File-private recording [MetadataRepository] for the preset ViewModel tests. Distinct from the
 * category test's fake to avoid a same-package redeclaration collision. Records preset writes so a
 * test can assert a validation-rejected submission never reaches the repository, and returns
 * [Result.success] on valid writes. Has no transaction-write surface (Requirement 15).
 */
private class PresetRecordingRepository(
    presets: List<QuickPreset> = emptyList()
) : MetadataRepository {

    private val walletsFlow = MutableStateFlow<List<Wallet>>(emptyList())
    private val categoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    private val presetsFlow = MutableStateFlow(presets)

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

    override suspend fun createCategory(fields: CategoryFields): Result<Unit> = Result.success(Unit)

    override suspend fun updateCategory(categoryId: Long, patch: CategoryPatch): Result<Unit> = Result.success(Unit)

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
