package com.alx.moneytracker.domain

/**
 * A labeled classification assigned to a [Transaction] (for example, "Makan").
 *
 * @property id Stable category identifier.
 * @property name Human-readable category name.
 * @property type The [TransactionType] this category applies to.
 * @property icon Identifier of the supporting icon.
 * @property isArchived Whether this category is archived and excluded from selection.
 */
data class Category(
    val id: Long,
    val name: String,
    val type: TransactionType,
    val icon: String,
    val isArchived: Boolean
)
