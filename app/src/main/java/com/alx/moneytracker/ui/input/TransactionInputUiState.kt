package com.alx.moneytracker.ui.input

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet

/**
 * Immutable UI state for the Transaction Input screen (MVVM).
 *
 * Rendered by the stateless Compose UI and owned by the ViewModel as a single
 * `StateFlow`. Amounts are non-negative integers in the smallest currency unit.
 *
 * @property runningAmount Current amount being entered, in the smallest currency unit.
 * @property selectedType Active [TransactionType]; defaults to EXPENSE (Requirement 3.2).
 * @property sourceWalletId Selected source wallet id; set to the default wallet on load (Requirement 4.1).
 * @property destWalletId Selected destination wallet id; only relevant for TRANSFER.
 * @property selectedCategoryId Selected category id, or null when none is chosen.
 * @property note Optional free-text note; persists as "" when empty (Requirement 6.4).
 * @property wallets Loaded, selectable wallets.
 * @property categories Loaded categories (already filtered to [selectedType]).
 * @property presets Configured quick-preset chips.
 * @property isSaving Whether a save is in progress.
 * @property errorMessage Transient error to surface to the UI, or null.
 */
data class TransactionInputUiState(
    val runningAmount: Long = 0L,
    val selectedType: TransactionType = TransactionType.EXPENSE,
    val sourceWalletId: Long? = null,
    val destWalletId: Long? = null,
    val selectedCategoryId: Long? = null,
    val note: String = "",
    val wallets: List<Wallet> = emptyList(),
    val categories: List<Category> = emptyList(),
    val presets: List<QuickPreset> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null
) {
    /**
     * Whether the Submit control should be enabled. Delegates to [SubmitValidator]
     * so the gating rule lives in one testable place (Requirement 7, 4.4).
     */
    val isSubmitEnabled: Boolean get() = SubmitValidator.isSubmitEnabled(this)
}
