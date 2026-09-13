package com.alx.moneytracker.ui.input

import com.alx.moneytracker.domain.TransactionType

/**
 * Pure gating logic for the Submit control.
 *
 * The selectable option lists (categories/wallets) live alongside this rule in
 * [SelectionFilters]. Together they form the Android-independent selection layer that the
 * property tests (Property 4 for the submit rule; Properties 5–7 for the filters) exercise
 * across the input space.
 */
object SubmitValidator {

    /**
     * The entry is submittable when [TransactionInputUiState.runningAmount] is greater than
     * zero AND a category is selected AND, for TRANSFER, a destination wallet is selected that
     * differs from the source wallet (Requirements 7.1, 7.2, 7.3, 4.4).
     */
    fun isSubmitEnabled(state: TransactionInputUiState): Boolean {
        val amountOk = state.runningAmount > 0L
        val categoryOk = state.selectedCategoryId != null
        val transferOk = state.selectedType != TransactionType.TRANSFER ||
            (state.destWalletId != null && state.destWalletId != state.sourceWalletId)
        return amountOk && categoryOk && transferOk
    }
}
