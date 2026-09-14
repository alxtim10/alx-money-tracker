package com.alx.moneytracker.ui.input

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alx.moneytracker.data.repository.TransactionRepository
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.logic.AmountReducer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID

/**
 * Owns and exposes the single immutable [TransactionInputUiState] for the Transaction Input screen
 * (MVVM). It reduces UI events into new state using the pure helpers [AmountReducer],
 * [SelectionFilters], and [SubmitValidator], and collects the repository's observable [kotlinx.coroutines.flow.Flow]s
 * (wallets, default wallet, categories, presets) into that state so the UI reflects data changes in
 * real time (Requirement 12.1).
 *
 * [clock] and [idGenerator] are injected so the transaction identity/timestamp assigned on submit
 * (Requirement 10) is deterministic under test; they are consumed by the submit orchestration
 * implemented in task 6.2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionInputViewModel(
    private val repository: TransactionRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransactionInputUiState())
    val uiState: StateFlow<TransactionInputUiState> = _uiState.asStateFlow()

    /**
     * Drives the category collector. Emitting a new type re-subscribes the category Flow for that
     * type via [flatMapLatest], so categories are always filtered to the active type (Requirements
     * 5.2, 5.3, 12).
     */
    private val selectedType = MutableStateFlow(TransactionType.EXPENSE)

    init {
        // Wallets: keep the full loaded (non-archived) list in state for selection.
        repository.observeWallets()
            .onEach { wallets ->
                _uiState.update { it.copy(wallets = SelectionFilters.selectableSourceWallets(wallets)) }
            }
            // A DB observation error (e.g. the database being torn down) must not crash the
            // app; stop updating this slice of state instead of propagating uncaught.
            .catch { }
            .launchIn(viewModelScope)

        // Default wallet: auto-select as the source only while the user hasn't chosen one
        // (Requirement 4.1). Never clobber a user-chosen source.
        repository.observeDefaultWallet()
            .onEach { default ->
                if (default != null) {
                    _uiState.update { state ->
                        if (state.sourceWalletId == null) state.copy(sourceWalletId = default.id) else state
                    }
                }
            }
            // A DB observation error (e.g. the database being torn down) must not crash the
            // app; stop updating this slice of state instead of propagating uncaught.
            .catch { }
            .launchIn(viewModelScope)

        // Quick presets.
        repository.observeQuickPresets()
            .onEach { presets -> _uiState.update { it.copy(presets = presets) } }
            // A DB observation error (e.g. the database being torn down) must not crash the
            // app; stop updating this slice of state instead of propagating uncaught.
            .catch { }
            .launchIn(viewModelScope)

        // Categories: re-subscribe whenever the selected type changes. The repository already
        // returns categories filtered by type; SelectionFilters.categoriesForType keeps the state
        // authoritative (non-archived, matching type).
        selectedType
            .flatMapLatest { type -> repository.observeCategories(type) }
            .onEach { categories ->
                _uiState.update { state ->
                    state.copy(categories = SelectionFilters.categoriesForType(categories, state.selectedType))
                }
            }
            // A DB observation error (e.g. the database being torn down) must not crash the
            // app; stop updating this slice of state instead of propagating uncaught.
            .catch { }
            .launchIn(viewModelScope)
    }

    /**
     * Reduces a UI [event] into a new [TransactionInputUiState]. Non-Submit events are handled here
     * fully; [TransactionInputEvent.Submit] orchestration (build + persist + reset) is implemented in
     * task 6.2.
     */
    fun onEvent(event: TransactionInputEvent) {
        when (event) {
            is TransactionInputEvent.DigitPressed ->
                _uiState.update { it.copy(runningAmount = AmountReducer.appendDigit(it.runningAmount, event.digit)) }

            TransactionInputEvent.DeletePressed ->
                _uiState.update { it.copy(runningAmount = AmountReducer.deleteDigit(it.runningAmount)) }

            is TransactionInputEvent.PresetTapped ->
                _uiState.update { it.copy(runningAmount = AmountReducer.addPreset(it.runningAmount, event.value)) }

            is TransactionInputEvent.TypeSelected -> {
                // Categories differ across types, so clear the selected category and re-filter for
                // the new type (Requirements 5.2, 5.3, 12).
                _uiState.update { it.copy(selectedType = event.type, selectedCategoryId = null) }
                selectedType.value = event.type
            }

            is TransactionInputEvent.SourceWalletSelected ->
                _uiState.update { it.copy(sourceWalletId = event.walletId) }

            is TransactionInputEvent.DestWalletSelected ->
                _uiState.update { it.copy(destWalletId = event.walletId) }

            is TransactionInputEvent.CategorySelected ->
                _uiState.update { it.copy(selectedCategoryId = event.categoryId) }

            is TransactionInputEvent.NoteChanged ->
                _uiState.update { it.copy(note = event.text) }

            TransactionInputEvent.ErrorConsumed ->
                _uiState.update { it.copy(errorMessage = null) }

            TransactionInputEvent.Submit -> handleSubmit()
        }
    }

    /**
     * Builds and persists the current entry (Requirement 10), then resets for the next entry on
     * success (Requirement 12.3). Guards against invalid state and a missing source wallet before
     * building so an unsubmittable entry is ignored (design Error Handling).
     */
    private fun handleSubmit() {
        val state = _uiState.value

        // Re-check the gating rule; ignore the event when the entry isn't submittable.
        if (!SubmitValidator.isSubmitEnabled(state)) return

        // Defensive: a submittable entry has a source wallet and category, but never build with a
        // null source/category.
        val sourceWalletId = state.sourceWalletId ?: return
        val categoryId = state.selectedCategoryId ?: return

        val transaction = Transaction(
            id = idGenerator(),
            timestamp = clock.millis(),
            type = state.selectedType,
            amount = state.runningAmount,
            sourceWalletId = sourceWalletId,
            destWalletId = if (state.selectedType == TransactionType.TRANSFER) state.destWalletId else null,
            categoryId = categoryId,
            note = state.note,
            isSynced = false
        )

        _uiState.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            // The repository switches to Dispatchers.IO internally; no main-thread blocking.
            repository.saveTransaction(transaction)
                .onSuccess {
                    // Reset amount and category for the next entry, preserving type and wallet
                    // selections (Requirement 12.3).
                    _uiState.update {
                        it.copy(
                            runningAmount = 0L,
                            selectedCategoryId = null,
                            isSaving = false,
                            errorMessage = null
                        )
                    }
                }
                .onFailure { cause ->
                    // Keep the entered state so the user can retry; surface the failure
                    // (Requirement 9.5).
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = cause.message ?: "Gagal menyimpan transaksi"
                        )
                    }
                }
        }
    }
}
