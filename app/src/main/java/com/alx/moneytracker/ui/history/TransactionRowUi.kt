package com.alx.moneytracker.ui.history

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.domain.logic.SyncStatusMapper

/**
 * One rendered entry in the Transaction_List.
 *
 * A [TransactionRowUi] is a pure presentation projection of a [Transaction]: the
 * source wallet, destination wallet, and category ids are resolved to their
 * human-readable names, and the raw `is_synced` value is mapped to a
 * [SyncStatus]. The amount stays a non-negative [Long] in the smallest currency
 * unit so the UI renders it as an integer (Requirement 1.4).
 *
 * The destination wallet name is present **only** for [TransactionType.TRANSFER]
 * rows and is `null` for EXPENSE and INCOME rows (Requirement 1.3).
 *
 * @property id The originating [Transaction.id].
 * @property type The [TransactionType] of the transaction (Requirement 1.2).
 * @property amount The transaction amount in the smallest currency unit (Requirements 1.2, 1.4).
 * @property sourceWalletName The resolved name of the source wallet (Requirement 1.2).
 * @property destWalletName The resolved name of the destination wallet; non-null only for
 *   [TransactionType.TRANSFER] (Requirement 1.3).
 * @property categoryName The resolved name of the category (Requirement 1.2).
 * @property timestamp Epoch-millisecond creation time, rendered as a date-time (Requirement 1.2).
 * @property syncStatus The [SyncStatus] derived from the transaction's `is_synced` value
 *   (Requirements 11.1, 11.2, 11.4).
 */
data class TransactionRowUi(
    val id: String,
    val type: TransactionType,
    val amount: Long,
    val sourceWalletName: String,
    val destWalletName: String?,
    val categoryName: String,
    val timestamp: Long,
    val syncStatus: SyncStatus
)

/** Placeholder label used when a referenced wallet is missing from the lookup. */
const val UNKNOWN_WALLET_LABEL: String = "(unknown wallet)"

/** Placeholder label used when a referenced category is missing from the lookup. */
const val UNKNOWN_CATEGORY_LABEL: String = "(unknown category)"

/**
 * Projects each [Transaction] in the receiver to a [TransactionRowUi], resolving
 * wallet and category ids to names via [wallets] and [categories].
 *
 * The mapping is pure and order-preserving: the resulting list has the same size
 * and the same ordering as the receiver. For each transaction:
 *
 * - the source wallet id is resolved to its [Wallet.name] (Requirement 1.2);
 * - the destination wallet name is included **only** for [TransactionType.TRANSFER]
 *   (Requirement 1.3), and is `null` otherwise even if a [Transaction.destWalletId]
 *   happens to be set;
 * - the category id is resolved to its [Category.name] (Requirement 1.2);
 * - the `is_synced` boolean is converted to `1`/`0` and mapped to a [SyncStatus] via
 *   [SyncStatusMapper] (Requirements 11.1, 11.2, 11.4);
 * - a referenced wallet or category that is absent from the lookups falls back to a
 *   placeholder label ([UNKNOWN_WALLET_LABEL] / [UNKNOWN_CATEGORY_LABEL]) so the row
 *   stays renderable rather than throwing.
 *
 * @param wallets the wallets available for name resolution.
 * @param categories the categories available for name resolution.
 * @return a list of [TransactionRowUi] mirroring the receiver's order and size.
 */
fun List<Transaction>.toRows(
    wallets: List<Wallet>,
    categories: List<Category>
): List<TransactionRowUi> {
    val walletNamesById = wallets.associateBy({ it.id }, { it.name })
    val categoryNamesById = categories.associateBy({ it.id }, { it.name })
    return map { transaction ->
        transaction.toRow(walletNamesById, categoryNamesById)
    }
}

/**
 * Projects a single [Transaction] to a [TransactionRowUi] using the pre-built
 * name lookups. Kept private so callers go through [toRows], which builds the
 * lookups once for the whole list.
 */
private fun Transaction.toRow(
    walletNamesById: Map<Long, String>,
    categoryNamesById: Map<Long, String>
): TransactionRowUi = TransactionRowUi(
    id = id,
    type = type,
    amount = amount,
    sourceWalletName = walletNamesById[sourceWalletId] ?: UNKNOWN_WALLET_LABEL,
    destWalletName = if (type == TransactionType.TRANSFER) {
        walletNamesById[destWalletId] ?: UNKNOWN_WALLET_LABEL
    } else {
        null
    },
    categoryName = categoryNamesById[categoryId] ?: UNKNOWN_CATEGORY_LABEL,
    timestamp = timestamp,
    syncStatus = SyncStatusMapper.statusOf(if (isSynced) 1 else 0)
)
