package com.alx.moneytracker.domain

/**
 * A named store of monetary value ("kantong") with an associated balance.
 *
 * @property id Stable wallet identifier.
 * @property name Human-readable wallet name.
 * @property balance Current balance in the smallest currency unit.
 * @property isDefault Whether this is the default source wallet for new transactions.
 * @property isArchived Whether this wallet is archived and excluded from selection.
 */
data class Wallet(
    val id: Long,
    val name: String,
    val balance: Long,
    val isDefault: Boolean,
    val isArchived: Boolean
)
