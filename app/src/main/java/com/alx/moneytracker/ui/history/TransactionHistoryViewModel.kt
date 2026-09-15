package com.alx.moneytracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alx.moneytracker.data.repository.HistoryRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.SortOrder
import com.alx.moneytracker.domain.SummaryTotals
import com.alx.moneytracker.domain.TimeFilter
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.domain.logic.SummaryAggregator
import com.alx.moneytracker.domain.logic.TransactionFilter
import com.alx.moneytracker.domain.logic.TransactionSorter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Owns and exposes the single immutable [HistoryUiState] for the read-only history screen (MVVM,
 * Scope 2).
 *
 * It holds the active [FilterSet] and [SortOrder] as in-memory [MutableStateFlow]s and builds
 * [uiState] by `combine`-ing the repository's observable transaction/wallet/category streams with
 * those two selection streams. Each recomputation applies the pure transforms
 * [TransactionFilter.apply] -> [TransactionSorter.sort] -> [SummaryAggregator.aggregate] -> row
 * mapping ([toRows]). Because observation is reactive, a Scope 1 write re-emits the transactions
 * stream and `combine` recomputes automatically without a manual refresh (Requirements 2.1, 2.2,
 * 10.5, 10.6, AGENTS.md Data Rule 2).
 *
 * The transform pipeline runs on [Dispatchers.IO] via [flowOn] so decoding and filtering/sorting/
 * aggregation never block the main thread; the resulting [StateFlow] is observed by Compose on the
 * main thread.
 *
 * The ViewModel performs no I/O beyond collecting the repository [kotlinx.coroutines.flow.Flow]s and
 * never calls a write method — the read-only boundary holds structurally (Requirement 12).
 */
class TransactionHistoryViewModel(
    private val repository: HistoryRepository
) : ViewModel() {

    private val filterSet = MutableStateFlow(FilterSet())
    private val sortOrder = MutableStateFlow(SortOrder.TIME_NEWEST_FIRST)

    /**
     * The last successfully rendered rows/totals. On a read failure the pipeline keeps these so the
     * UI preserves the previously displayed list while showing an error indication (Requirements
     * 1.7, 12.4). Access is confined to the single-threaded [combine] transform, so a plain field
     * is sufficient.
     */
    private var lastGoodRows: List<TransactionRowUi> = emptyList()
    private var lastGoodTotals: SummaryTotals = SummaryTotals()

    val uiState: StateFlow<HistoryUiState> = combine(
        repository.observeTransactions(),
        repository.observeWallets(),
        repository.observeCategories(),
        filterSet,
        sortOrder
    ) { txResult, wallets, categories, filters, sort ->
        buildState(txResult, wallets, categories, filters, sort)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Initial)

    /**
     * Orchestration seam over the pure transforms. On success it filters, sorts, aggregates, and
     * maps rows, caching the result as the last-good state. On failure it keeps the last-good
     * rows/totals and flags [HistoryUiState.isError] (Requirements 1.7, 12.4). It never mutates the
     * store.
     */
    private fun buildState(
        txResult: Result<List<Transaction>>,
        wallets: List<Wallet>,
        categories: List<Category>,
        filters: FilterSet,
        sort: SortOrder
    ): HistoryUiState = txResult.fold(
        onSuccess = { all ->
            val filtered = TransactionFilter.apply(all, filters)
            val ordered = TransactionSorter.sort(filtered, sort)
            val totals = SummaryAggregator.aggregate(filtered)
            val rows = ordered.toRows(wallets, categories)
            lastGoodRows = rows
            lastGoodTotals = totals
            HistoryUiState(
                rows = rows,
                totals = totals,
                filterSet = filters,
                sortOrder = sort,
                availableWallets = wallets,
                availableCategories = categories,
                isError = false
            )
        },
        onFailure = {
            HistoryUiState(
                rows = lastGoodRows,
                totals = lastGoodTotals,
                filterSet = filters,
                sortOrder = sort,
                availableWallets = wallets,
                availableCategories = categories,
                isError = true
            )
        }
    )

    /**
     * Reduces a UI [event] into the active [FilterSet]/[SortOrder]. Chip toggles flip membership
     * (add on activate, remove on deactivate — Requirements 3.4, 4.3, 5.4, 6.6); the time events
     * set/clear the [TimeFilter] (Requirement 7.4); [HistoryEvent.SortSelected] replaces the single
     * [SortOrder] while leaving the [FilterSet] untouched (Requirement 9.7).
     */
    fun onEvent(event: HistoryEvent) {
        when (event) {
            is HistoryEvent.TypeChipToggled ->
                filterSet.update { it.copy(types = it.types.toggle(event.type)) }

            is HistoryEvent.WalletChipToggled ->
                filterSet.update { it.copy(walletIds = it.walletIds.toggle(event.walletId)) }

            is HistoryEvent.CategoryChipToggled ->
                filterSet.update { it.copy(categoryIds = it.categoryIds.toggle(event.categoryId)) }

            is HistoryEvent.SyncChipToggled ->
                filterSet.update { it.copy(syncStatuses = it.syncStatuses.toggle(event.status)) }

            is HistoryEvent.TimeRangeSelected ->
                filterSet.update {
                    it.copy(timeFilter = TimeFilter(event.startInclusive, event.endInclusive))
                }

            HistoryEvent.TimeFilterCleared ->
                filterSet.update { it.copy(timeFilter = null) }

            is HistoryEvent.SortSelected ->
                sortOrder.value = event.order
        }
    }

    /** Adds [element] when absent, removes it when present — a set-membership toggle. */
    private fun <T> Set<T>.toggle(element: T): Set<T> =
        if (contains(element)) this - element else this + element
}
