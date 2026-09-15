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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
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
 * Unit tests for [WalletManagementViewModel] state and event handling (Task 9.2).
 *
 * These are example/edge-case unit tests (not property tests) pinning down the ViewModel's
 * event-reduction contract:
 *  - an invalid name on [WalletManagementEvent.CreateWallet]/[WalletManagementEvent.RenameWallet]
 *    surfaces its [ValidationError] in `state.error` and never reaches the repository, so the
 *    store is left unchanged (Requirements 1.2, 1.3),
 *  - an invalid override balance surfaces `BALANCE_OUT_OF_RANGE` and never reaches the repository
 *    (Requirement 4.2),
 *  - a null `initialBalance` on create is coerced to `0L` (a valid balance) and DOES persist
 *    (Requirement 1.4),
 *  - a repository [Result.failure] on a valid write surfaces `state.operationFailed` (Requirements
 *    4.7, 6.5),
 *  - [WalletManagementEvent.ErrorConsumed] clears both `error` and `operationFailed`.
 *
 * The ViewModel builds `uiState` by `combine`-ing `repository.observeWallets()` with an in-memory
 * feedback stream, then `flowOn(Dispatchers.IO)` and `stateIn(..., WhileSubscribed(5000), Initial)`.
 * Two consequences shape the harness, mirroring the Scope 2 ViewModel tests:
 *  1. `WhileSubscribed` means `uiState` only recomputes beyond [WalletManagementUiState.Initial]
 *     while a collector is active, so each test launches a collector and cancels it at the end.
 *  2. `flowOn(Dispatchers.IO)` runs the combine on a real background dispatcher, so emissions are
 *     asynchronous relative to the test thread — the tests suspend on [awaitState] (a bounded
 *     `first { predicate }`) rather than reading `uiState.value` immediately.
 *
 * Because the pipeline runs on a real dispatcher, the tests use [runBlocking] (real time) rather
 * than a virtual-time scheduler; the Main dispatcher is set to an [UnconfinedTestDispatcher] so
 * `viewModelScope` work is driven eagerly. A [FakeMetadataRepository] records every write call and
 * can be armed to return [Result.failure], letting the tests assert both "the repository was never
 * called" and "a persistence failure surfaces operationFailed".
 *
 * Validates: Requirements 1.2, 1.3, 1.4, 4.2, 4.7, 6.5
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletManagementViewModelTest : FunSpec({

    beforeTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    afterTest {
        Dispatchers.resetMain()
    }

    val existingWallet = Wallet(
        id = 1L,
        name = "Utama",
        balance = 5_000L,
        isDefault = true,
        isArchived = false
    )

    fun newRepository(): FakeMetadataRepository =
        FakeMetadataRepository(wallets = listOf(existingWallet))

    test("an invalid (blank) name on CreateWallet sets error NAME_EMPTY and never calls the repository (Req 1.2)") {
        runBlocking {
            val repository = newRepository()
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(WalletManagementEvent.CreateWallet(name = "   ", initialBalance = 100L))

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.NAME_EMPTY
            // The store is left unchanged: no write ever reached the repository (Requirement 1.2).
            repository.createWalletCalls.shouldBeEmpty()

            collector.cancel()
        }
    }

    test("an over-long name on CreateWallet sets error NAME_TOO_LONG and never calls the repository (Req 1.2)") {
        runBlocking {
            val repository = newRepository()
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            val tooLong = "x".repeat(MetadataValidator.WALLET_NAME_MAX + 1)
            viewModel.onEvent(WalletManagementEvent.CreateWallet(name = tooLong, initialBalance = 0L))

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.NAME_TOO_LONG
            repository.createWalletCalls.shouldBeEmpty()

            collector.cancel()
        }
    }

    test("an out-of-range balance on CreateWallet sets error BALANCE_OUT_OF_RANGE and never calls the repository (Req 1.3)") {
        runBlocking {
            val repository = newRepository()
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(WalletManagementEvent.CreateWallet(name = "Tabungan", initialBalance = -1L))

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.BALANCE_OUT_OF_RANGE
            repository.createWalletCalls.shouldBeEmpty()

            collector.cancel()
        }
    }

    test("an invalid override balance sets error BALANCE_OUT_OF_RANGE and never calls the repository (Req 4.2)") {
        runBlocking {
            val repository = newRepository()
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(
                WalletManagementEvent.OverrideBalance(
                    walletId = existingWallet.id,
                    targetBalance = MetadataValidator.MAX_MONEY + 1
                )
            )

            val state = viewModel.uiState.awaitState { it.error != null }
            state.error shouldBe ValidationError.BALANCE_OUT_OF_RANGE
            repository.overrideBalanceCalls.shouldBeEmpty()

            collector.cancel()
        }
    }

    test("a null initialBalance on CreateWallet is coerced to 0 and DOES persist (Req 1.4)") {
        runBlocking {
            val repository = newRepository()
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(WalletManagementEvent.CreateWallet(name = "Tunai", initialBalance = null))

            // A null initial balance is a valid 0L, so the create reaches the repository (no error).
            val state = viewModel.uiState.awaitState { repository.createWalletCalls.isNotEmpty() }
            state.error shouldBe null
            state.operationFailed.shouldBeFalse()
            repository.createWalletCalls.shouldContainExactly(
                NewWallet(name = "Tunai", balance = 0L)
            )

            collector.cancel()
        }
    }

    test("a repository failure on a valid create sets operationFailed (Req 4.7, 6.5)") {
        runBlocking {
            val repository = newRepository()
            repository.failNextWrite(RuntimeException("insert failed"))
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(WalletManagementEvent.CreateWallet(name = "Tabungan", initialBalance = 1_000L))

            val state = viewModel.uiState.awaitState { it.operationFailed }
            state.operationFailed.shouldBeTrue()
            // No validation error — the failure came from persistence, not validation.
            state.error shouldBe null
            // The write path was still attempted (a valid submission reached the repository).
            repository.createWalletCalls.shouldContainExactly(
                NewWallet(name = "Tabungan", balance = 1_000L)
            )

            collector.cancel()
        }
    }

    test("ErrorConsumed clears a surfaced validation error") {
        runBlocking {
            val repository = newRepository()
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(WalletManagementEvent.RenameWallet(walletId = existingWallet.id, name = ""))
            viewModel.uiState.awaitState { it.error == ValidationError.NAME_EMPTY }

            viewModel.onEvent(WalletManagementEvent.ErrorConsumed)

            val state = viewModel.uiState.awaitState { it.error == null }
            state.error shouldBe null
            state.operationFailed.shouldBeFalse()

            collector.cancel()
        }
    }

    test("ErrorConsumed clears a surfaced operationFailed") {
        runBlocking {
            val repository = newRepository()
            repository.failNextWrite(RuntimeException("update failed"))
            val viewModel = WalletManagementViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.wallets.isNotEmpty() }

            viewModel.onEvent(WalletManagementEvent.SetDefaultWallet(walletId = existingWallet.id))
            viewModel.uiState.awaitState { it.operationFailed }

            viewModel.onEvent(WalletManagementEvent.ErrorConsumed)

            val state = viewModel.uiState.awaitState { !it.operationFailed }
            state.operationFailed.shouldBeFalse()
            state.error shouldBe null

            collector.cancel()
        }
    }
})

/**
 * Suspends until [uiState] emits a [WalletManagementUiState] satisfying [predicate], returning that
 * state. Bounded by a timeout so a never-satisfied predicate fails fast instead of hanging. Needed
 * because the ViewModel's pipeline runs on a real background dispatcher (`flowOn(Dispatchers.IO)`),
 * so state updates are asynchronous relative to the test thread.
 */
private suspend fun StateFlow<WalletManagementUiState>.awaitState(
    predicate: (WalletManagementUiState) -> Boolean
): WalletManagementUiState = withTimeout(5_000) { first(predicate) }

/**
 * File-private fake [MetadataRepository] backed by a [MutableStateFlow] of wallets. It records the
 * arguments of every wallet write so a test can assert both that an invalid submission never
 * reached persistence and that a valid one did, and it can be armed via [failNextWrite] to return
 * [Result.failure] from the next wallet write (exercising the operationFailed path). Only the
 * wallet surface is used by [WalletManagementViewModel]; the category/preset members are inert.
 */
private class FakeMetadataRepository(
    wallets: List<Wallet>
) : MetadataRepository {

    private val walletsFlow = MutableStateFlow(wallets)

    val createWalletCalls = mutableListOf<NewWallet>()
    val renameWalletCalls = mutableListOf<Pair<Long, String>>()
    val overrideBalanceCalls = mutableListOf<Pair<Long, Long>>()
    val archiveWalletCalls = mutableListOf<Long>()
    val setDefaultWalletCalls = mutableListOf<Long>()

    private var pendingFailure: Throwable? = null

    /** Arms the next wallet write to return [Result.failure] with [cause]. */
    fun failNextWrite(cause: Throwable) {
        pendingFailure = cause
    }

    private fun nextResult(): Result<Unit> {
        val failure = pendingFailure
        return if (failure != null) {
            pendingFailure = null
            Result.failure(failure)
        } else {
            Result.success(Unit)
        }
    }

    override fun observeWallets(): Flow<List<Wallet>> = walletsFlow

    override fun observeCategories(): Flow<List<Category>> =
        MutableStateFlow(emptyList())

    override fun observeQuickPresets(): Flow<List<QuickPreset>> =
        MutableStateFlow(emptyList())

    override suspend fun createWallet(wallet: NewWallet): Result<Unit> {
        createWalletCalls += wallet
        return nextResult()
    }

    override suspend fun renameWallet(walletId: Long, name: String): Result<Unit> {
        renameWalletCalls += (walletId to name)
        return nextResult()
    }

    override suspend fun overrideBalance(walletId: Long, target: Long): Result<Unit> {
        overrideBalanceCalls += (walletId to target)
        return nextResult()
    }

    override suspend fun archiveWallet(walletId: Long): Result<Unit> {
        archiveWalletCalls += walletId
        return nextResult()
    }

    override suspend fun setDefaultWallet(walletId: Long): Result<Unit> {
        setDefaultWalletCalls += walletId
        return nextResult()
    }

    override suspend fun createCategory(fields: CategoryFields): Result<Unit> = Result.success(Unit)

    override suspend fun updateCategory(categoryId: Long, patch: CategoryPatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun archiveCategory(categoryId: Long): Result<Unit> = Result.success(Unit)

    override suspend fun createPreset(fields: PresetFields): Result<Unit> = Result.success(Unit)

    override suspend fun updatePreset(presetId: Long, patch: PresetPatch): Result<Unit> =
        Result.success(Unit)

    override suspend fun deletePreset(presetId: Long): Result<Unit> = Result.success(Unit)
}
