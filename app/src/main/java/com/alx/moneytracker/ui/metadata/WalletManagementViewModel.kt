package com.alx.moneytracker.ui.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alx.moneytracker.data.repository.MetadataRepository
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.ValidationResult
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.domain.logic.MetadataValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI events for the wallet management surface (Scope 3).
 *
 * Numeric fields are non-negative smallest-unit [Long] values. [CreateWallet.initialBalance] is
 * nullable at the event boundary: a null initial balance is coerced to `0L` before validation in
 * the ViewModel (Requirement 1.4).
 */
sealed interface WalletManagementEvent {

    /** Create a wallet with the given name and optional initial balance (null -> 0, Req 1.4). */
    data class CreateWallet(val name: String, val initialBalance: Long?) : WalletManagementEvent

    /** Rename wallet [walletId] to [name] (Req 3). */
    data class RenameWallet(val walletId: Long, val name: String) : WalletManagementEvent

    /** Directly overwrite wallet [walletId]'s balance with [targetBalance] (Req 4, no transaction). */
    data class OverrideBalance(val walletId: Long, val targetBalance: Long) : WalletManagementEvent

    /** Archive (soft-delete) wallet [walletId] (Req 5). */
    data class ArchiveWallet(val walletId: Long) : WalletManagementEvent

    /** Designate wallet [walletId] as the single default (Req 6). */
    data class SetDefaultWallet(val walletId: Long) : WalletManagementEvent

    /** Clear a surfaced validation error / operation-failure indication after the UI shows it. */
    data object ErrorConsumed : WalletManagementEvent
}

/**
 * Immutable UI state for the wallet management screen.
 *
 * @property wallets the current Active_Wallet collection from [MetadataRepository.observeWallets].
 * @property error set to the offending [ValidationError] when a create/rename/override submission
 *   is rejected by [MetadataValidator]; null otherwise (Req 1.2, 1.3, 3.2, 4.2).
 * @property operationFailed set when a persistence [Result] is a failure so the affected store is
 *   left in its prior state and the UI can surface the failure (Req 4.7, 6.5).
 */
data class WalletManagementUiState(
    val wallets: List<Wallet> = emptyList(),
    val error: ValidationError? = null,
    val operationFailed: Boolean = false
) {
    companion object {
        val Initial = WalletManagementUiState()
    }
}

/**
 * Owns and exposes the single immutable [WalletManagementUiState] for the wallet management screen
 * (MVVM, Scope 3).
 *
 * [uiState] is built by `combine`-ing the repository's observable Active_Wallet stream with an
 * in-memory error/failure stream: Room re-emits on any wallet change so a create / rename / archive
 * / override / set-default propagates live without a manual refresh (AGENTS.md Data Rule 2,
 * Requirements 2.1, 2.4). The observation runs on [Dispatchers.IO] via [flowOn] so decoding never
 * blocks the main thread, and the result is shared as a [StateFlow] via
 * [SharingStarted.WhileSubscribed].
 *
 * In [onEvent], every mutating submission is validated by [MetadataValidator] before it can reach
 * persistence: an [ValidationResult.Invalid] surfaces its reason in [WalletManagementUiState.error]
 * and never calls the repository (the store is left unchanged), while a repository
 * [Result.failure] surfaces [WalletManagementUiState.operationFailed]. The null initial balance is
 * coerced to `0L` here in the [WalletManagementEvent.CreateWallet] handler before validation
 * (Requirement 1.4). The ViewModel reaches only the metadata write surface of [MetadataRepository]
 * and never touches the `transactions` table (Requirement 15, by construction).
 */
class WalletManagementViewModel(
    private val repository: MetadataRepository
) : ViewModel() {

    /**
     * Transient error/failure indication merged into [uiState]. Cleared by
     * [WalletManagementEvent.ErrorConsumed]. Kept separate from the reactive wallet stream so a
     * fresh wallet emission does not clobber a pending error.
     */
    private data class Feedback(val error: ValidationError? = null, val operationFailed: Boolean = false)

    private val feedback = MutableStateFlow(Feedback())

    val uiState: StateFlow<WalletManagementUiState> = combine(
        repository.observeWallets(),
        feedback
    ) { wallets, fb ->
        WalletManagementUiState(
            wallets = wallets,
            error = fb.error,
            operationFailed = fb.operationFailed
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WalletManagementUiState.Initial)

    /**
     * Reduces a UI [event] into the wallet store (via the repository) or the transient feedback
     * stream. Each mutating event validates first; only a [ValidationResult.Valid] result reaches
     * persistence, and the resulting [Result] is mapped into [Feedback.operationFailed] on failure.
     */
    fun onEvent(event: WalletManagementEvent) {
        when (event) {
            is WalletManagementEvent.CreateWallet -> {
                // Req 1.4: a null initial balance is treated as 0 before validation.
                val balance = event.initialBalance ?: 0L
                when (val v = MetadataValidator.validateWalletCreate(event.name, balance)) {
                    is ValidationResult.Invalid -> setError(v.reason)          // Req 1.2, 1.3
                    is ValidationResult.Valid -> persist { repository.createWallet(v.value) }
                }
            }

            is WalletManagementEvent.RenameWallet ->
                when (val v = MetadataValidator.validateWalletName(event.name)) {
                    is ValidationResult.Invalid -> setError(v.reason)          // Req 3.2
                    is ValidationResult.Valid -> persist { repository.renameWallet(event.walletId, v.value) }
                }

            is WalletManagementEvent.OverrideBalance ->
                when (val v = MetadataValidator.validateBalance(event.targetBalance)) {
                    is ValidationResult.Invalid -> setError(v.reason)          // Req 4.2
                    is ValidationResult.Valid -> persist { repository.overrideBalance(event.walletId, v.value) }
                }

            is WalletManagementEvent.ArchiveWallet ->
                persist { repository.archiveWallet(event.walletId) }           // Req 6.4 (reassignment in repo)

            is WalletManagementEvent.SetDefaultWallet ->
                persist { repository.setDefaultWallet(event.walletId) }        // Req 6.4

            WalletManagementEvent.ErrorConsumed ->
                feedback.update { Feedback() }
        }
    }

    /** Surfaces a validation [reason] without touching the store (Req 1.2, 1.3, 3.2, 4.2). */
    private fun setError(reason: ValidationError) {
        feedback.update { it.copy(error = reason) }
    }

    /**
     * Runs a repository write in [viewModelScope], mapping a [Result.failure] into
     * [Feedback.operationFailed] and clearing prior feedback on success (Req 4.7, 6.5). The
     * repository switches to [Dispatchers.IO] internally, so the main thread is never blocked.
     */
    private fun persist(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            block()
                .onSuccess { feedback.update { Feedback() } }
                .onFailure { feedback.update { it.copy(operationFailed = true) } }
        }
    }
}
