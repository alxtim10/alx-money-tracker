package com.alx.moneytracker.domain

/**
 * Write-side value types for the Customization & Metadata feature (Scope 3).
 *
 * These are validated submissions produced by `MetadataValidator` and consumed by the metadata
 * repository. They reuse Scope 1's [TransactionType] enum and are kept in the Android-independent
 * `domain` package so they are testable on the JVM. Balances and amounts are non-negative `Long`
 * values in the smallest currency unit, consistent with Scope 1 & 2.
 */

/**
 * A validated new wallet ready to persist. The `is_default` value is resolved by the repository
 * per Requirement 1.5 (first active wallet becomes the default), so it is not carried here.
 *
 * @property name Trimmed, validated wallet name (1..100 characters).
 * @property balance Initial balance in the smallest currency unit (0..MAX_MONEY).
 */
data class NewWallet(
    val name: String,
    val balance: Long
)

/**
 * Validated category fields for creation. [type] is guaranteed to be INCOME or EXPENSE
 * (never TRANSFER) per Requirement 7.3.
 *
 * @property name Trimmed, validated category name (1..50 characters).
 * @property type The category [TransactionType]; INCOME or EXPENSE only.
 * @property icon Optional icon identifier; null when no icon was submitted (Requirement 7.4).
 */
data class CategoryFields(
    val name: String,
    val type: TransactionType,
    val icon: String?
)

/**
 * A partial category update. A null field means "leave unchanged" per Requirement 9.1.
 *
 * @property name New trimmed name, or null to keep the existing name.
 * @property type New [TransactionType] (INCOME or EXPENSE), or null to keep the existing type.
 * @property icon New icon identifier, or null to keep the existing icon.
 */
data class CategoryPatch(
    val name: String? = null,
    val type: TransactionType? = null,
    val icon: String? = null
)

/**
 * Validated quick preset fields for creation.
 *
 * @property amount Preset amount in the smallest currency unit (1..MAX_MONEY).
 * @property label Trimmed, validated label (1..50 characters).
 */
data class PresetFields(
    val amount: Long,
    val label: String
)

/**
 * A partial quick preset update. A null field means "leave unchanged" per Requirement 13.1.
 *
 * @property amount New amount, or null to keep the existing amount.
 * @property label New trimmed label, or null to keep the existing label.
 */
data class PresetPatch(
    val amount: Long? = null,
    val label: String? = null
)
