package com.alx.moneytracker.ui.input

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet

/**
 * Pure, Android-independent helpers that derive the selectable option lists shown in the
 * transaction input UI. Keeping these functions here (rather than inside a ViewModel) makes
 * them straightforward to exercise on the JVM via unit and property tests (Properties 5, 6, 7).
 */
object SelectionFilters {

    /**
     * Categories offered for a given [type]: non-archived categories whose
     * [Category.type] matches [type], preserving the input order.
     *
     * Property 5 (Requirements 5.2, 5.3).
     */
    fun categoriesForType(categories: List<Category>, type: TransactionType): List<Category> =
        categories.filter { !it.isArchived && it.type == type }

    /**
     * Wallets selectable as the source ("dari"): all non-archived wallets, preserving order.
     *
     * Property 7 (Requirement 4.2).
     */
    fun selectableSourceWallets(wallets: List<Wallet>): List<Wallet> =
        wallets.filter { !it.isArchived }

    /**
     * Wallets selectable as the TRANSFER destination ("ke"): non-archived wallets excluding the
     * wallet whose [Wallet.id] equals [sourceWalletId] (so a transfer cannot target its own
     * source). When [sourceWalletId] is null nothing is excluded beyond archived wallets.
     *
     * Property 7 (Requirement 4.3).
     */
    fun selectableDestWallets(wallets: List<Wallet>, sourceWalletId: Long?): List<Wallet> =
        wallets.filter { !it.isArchived && it.id != sourceWalletId }
}
