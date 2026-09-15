package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.Wallet

/**
 * Pure selection logic for choosing a replacement Default_Wallet when the current
 * default is archived.
 *
 * The object holds no Android dependencies so it can be exercised directly on the
 * JVM by unit and property tests.
 *
 * Given the set of Active_Wallet records that remain after the current
 * Default_Wallet has been archived, [pickReplacementDefault] returns the
 * identifier of the wallet that should become the new default: the one whose name
 * sorts first in case-insensitive ascending order, with ties broken by the lowest
 * wallet identifier. When no active wallet remains, no wallet is designated and
 * the function returns `null` (Requirements 5.5, 5.6).
 */
object DefaultWalletResolver {

    /**
     * Selects the identifier of the wallet that should become the new
     * Default_Wallet from the [remainingActive] wallets.
     *
     * The chosen wallet is the one whose [Wallet.name] sorts first in
     * case-insensitive ascending order; ties in name are broken by the lowest
     * [Wallet.id]. The selection is a deterministic total order, so the same set
     * of wallets always yields the same result regardless of the input's initial
     * arrangement.
     *
     * @param remainingActive the Active_Wallet records remaining after the current
     *   Default_Wallet is archived; may be empty.
     * @return the [Wallet.id] of the wallet to designate as the new
     *   Default_Wallet, or `null` when [remainingActive] is empty (Requirements
     *   5.5, 5.6).
     */
    fun pickReplacementDefault(remainingActive: List<Wallet>): Long? =
        remainingActive
            .minWithOrNull(
                compareBy<Wallet>({ it.name.lowercase() }, { it.id })
            )
            ?.id
}
