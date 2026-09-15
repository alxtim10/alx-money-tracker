package com.alx.moneytracker.ui.history

import com.alx.moneytracker.data.repository.HistoryRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldHaveSize
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
 * Unit tests for [TransactionHistoryViewModel] state and event handling (Task 10.2).
 *
 * These are example/edge-case unit tests (not property tests) pinning down the ViewModel's
 * state contract and event reduction:
 *  - the default [SortOrder] is [SortOrder.TIME_NEWEST_FIRST] (Requirement 9.2),
 *  - the initial [com.alx.moneytracker.domain.FilterSet] has both Sync_Status selections inactive
 *    and no Time_Filter (Requirement 6.2),
 *  - selecting a [SortOrder] leaves exactly one Sort_Order active (Requirement 9.1),
 *  - a read-failure emission sets `isError = true` and retains the previously displayed rows
 *    (Requirements 1.7, 12.4),
 *  - changing the [SortOrder] preserves the active Filter_Set (Requirement 9.7).
 *
 * The ViewModel builds `uiState` with `flowOn(Dispatchers.IO)` and
 * `stateIn(..., WhileSubscribed(5000), Initial)`. Two consequences shape the test harness:
 *  1. `WhileSubscribed` means `uiState` only recomputes beyond [HistoryUiState.Initial] while a
 *     collector is active, so each test launches a collector and cancels it at the end.
 *  2. `flowOn(Dispatchers.IO)` runs the combine/transform pipeline on a real background
 *     dispatcher, so emissions are asynchronous relative to the test thread. Reading
 *     `uiState.value` immediately after an event can observe stale state; instead the tests
 *     suspend until the expected state arrives via [awaitState], which collects `uiState` until a
 *     predicate holds (bounded by a real-time timeout).
 *
 * Because the pipeline runs on a real dispatcher, the tests use [runBlocking] (real time) rather
 * than a virtual-time test scheduler — otherwise [withTimeout] would fast-forward and fire before
 * the background work completes. The Main dispatcher is still set to an [UnconfinedTestDispatcher]
 * so `viewModelScope` work is driven eagerly. A [FakeHistoryRepository] backed by
 * [MutableStateFlow]s gives the tests full control over the transaction/wallet/category emissions,
 * including a [Result.failure] to exercise the read-failure path.
 *
 * Validates: Requirements 6.2, 9.1, 9.2, 9.7, 1.7, 12.4
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionHistoryViewModelTest : FunSpec({

    beforeTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    afterTest {
        Dispatchers.resetMain()
    }

    val wallet = Wallet(id = 1L, name = "Utama", balance = 0L, isDefault = true, isArchived = false)
    val category = Category(
        id = 10L,
        name = "Makan",
        type = TransactionType.EXPENSE,
        icon = "food",
        isArchived = false
    )

    fun transaction(
        id: String,
        amount: Long,
        timestamp: Long
    ): Transaction = Transaction(
        id = id,
        timestamp = timestamp,
        type = TransactionType.EXPENSE,
        amount = amount,
        sourceWalletId = wallet.id,
        destWalletId = null,
        categoryId = category.id,
        note = "",
        isSynced = false
    )

    fun newRepository(
        transactions: List<Transaction> = listOf(transaction("a", amount = 100L, timestamp = 1_000L))
    ): FakeHistoryRepository = FakeHistoryRepository(
        transactions = transactions,
        wallets = listOf(wallet),
        categories = listOf(category)
    )

    test("default Sort_Order is TIME_NEWEST_FIRST (Req 9.2)") {
        runBlocking {
            val viewModel = TransactionHistoryViewModel(newRepository())
            val collector = launch { viewModel.uiState.collect { } }

            // The first non-Initial emission carries the default Sort_Order.
            val state = viewModel.uiState.awaitState { it.rows.isNotEmpty() }
            state.sortOrder shouldBe SortOrder.TIME_NEWEST_FIRST

            collector.cancel()
        }
    }

    test("initial Filter_Set has both Sync_Status selections inactive and no Time_Filter (Req 6.2)") {
        runBlocking {
            val viewModel = TransactionHistoryViewModel(newRepository())
            val collector = launch { viewModel.uiState.collect { } }

            val filterSet = viewModel.uiState.awaitState { it.rows.isNotEmpty() }.filterSet
            filterSet.syncStatuses.shouldHaveSize(0)
            filterSet.timeFilter shouldBe null

            collector.cancel()
        }
    }

    test("selecting a Sort_Order leaves exactly one Sort_Order active (Req 9.1)") {
        runBlocking {
            val viewModel = TransactionHistoryViewModel(newRepository())
            val collector = launch { viewModel.uiState.collect { } }

            viewModel.uiState.awaitState { it.rows.isNotEmpty() }

            viewModel.onEvent(HistoryEvent.SortSelected(SortOrder.AMOUNT_LARGEST_FIRST))
            // A single SortOrder value is exposed — replacing, not accumulating (Requirement 9.1).
            viewModel.uiState.awaitState { it.sortOrder == SortOrder.AMOUNT_LARGEST_FIRST }

            viewModel.onEvent(HistoryEvent.SortSelected(SortOrder.TIME_OLDEST_FIRST))
            viewModel.uiState.awaitState { it.sortOrder == SortOrder.TIME_OLDEST_FIRST }

            collector.cancel()
        }
    }

    test("a read-failure emission sets isError=true and retains previous rows (Req 1.7, 12.4)") {
        runBlocking {
            val transactions = listOf(
                transaction("a", amount = 100L, timestamp = 2_000L),
                transaction("b", amount = 200L, timestamp = 1_000L)
            )
            val repository = newRepository(transactions)
            val viewModel = TransactionHistoryViewModel(repository)
            val collector = launch { viewModel.uiState.collect { } }

            // A successful read is rendered first, so there are rows to retain.
            val goodState = viewModel.uiState.awaitState { !it.isError && it.rows.isNotEmpty() }
            val goodRows = goodState.rows
            goodRows.shouldHaveSize(2)

            // The read now fails.
            repository.emitTransactionsFailure(RuntimeException("read failed"))

            val errorState = viewModel.uiState.awaitState { it.isError }
            // The previously displayed rows are preserved (Requirements 1.7, 12.4).
            errorState.rows shouldBe goodRows

            collector.cancel()
        }
    }

    test("changing the Sort_Order preserves the active Filter_Set (Req 9.7)") {
        runBlocking {
            val viewModel = TransactionHistoryViewModel(newRepository())
            val collector = launch { viewModel.uiState.collect { } }

            // Activate a couple of filter dimensions before changing the sort.
            viewModel.onEvent(HistoryEvent.TypeChipToggled(TransactionType.EXPENSE))
            viewModel.onEvent(HistoryEvent.WalletChipToggled(wallet.id))
            val filterBefore = viewModel.uiState.awaitState {
                it.filterSet.types.contains(TransactionType.EXPENSE) &&
                    it.filterSet.walletIds.contains(wallet.id)
            }.filterSet

            viewModel.onEvent(HistoryEvent.SortSelected(SortOrder.AMOUNT_SMALLEST_FIRST))

            val stateAfter = viewModel.uiState.awaitState {
                it.sortOrder == SortOrder.AMOUNT_SMALLEST_FIRST
            }
            // The Filter_Set is untouched by a sort change (Requirement 9.7).
            stateAfter.filterSet shouldBe filterBefore
            stateAfter.isError.shouldBeFalse()

            collector.cancel()
        }
    }
})

/**
 * Suspends until [uiState] emits a [HistoryUiState] satisfying [predicate], returning that state.
 * Bounded by a timeout so a never-satisfied predicate fails fast instead of hanging. Needed because
 * the ViewModel's pipeline runs on a real background dispatcher (`flowOn(Dispatchers.IO)`), so state
 * updates are asynchronous relative to the test thread.
 */
private suspend fun StateFlow<HistoryUiState>.awaitState(
    predicate: (HistoryUiState) -> Boolean
): HistoryUiState = withTimeout(5_000) { first(predicate) }

/**
 * File-private fake [HistoryRepository] backed by [MutableStateFlow]s so a test can drive the
 * observed transactions/wallets/categories deterministically and, in particular, push a
 * [Result.failure] onto the transactions stream to exercise the read-failure path.
 */
private class FakeHistoryRepository(
    transactions: List<Transaction>,
    wallets: List<Wallet>,
    categories: List<Category>
) : HistoryRepository {

    private val transactionsFlow =
        MutableStateFlow<Result<List<Transaction>>>(Result.success(transactions))
    private val walletsFlow = MutableStateFlow(wallets)
    private val categoriesFlow = MutableStateFlow(categories)

    override fun observeTransactions(): Flow<Result<List<Transaction>>> = transactionsFlow

    override fun observeWallets(): Flow<List<Wallet>> = walletsFlow

    override fun observeCategories(): Flow<List<Category>> = categoriesFlow

    /** Emits a read failure onto the transactions stream (Requirements 1.7, 12.4). */
    fun emitTransactionsFailure(cause: Throwable) {
        transactionsFlow.value = Result.failure(cause)
    }
}
