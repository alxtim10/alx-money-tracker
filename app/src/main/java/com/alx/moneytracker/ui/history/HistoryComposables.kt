package com.alx.moneytracker.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.SummaryTotals
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.ui.input.components.formatAmount
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stateless leaf composables for the Transaction History screen (Scope 2).
 *
 * These are pure rendering functions driven entirely by their arguments — no business logic, no
 * state ownership. The filter chip header/sort control, the `LazyColumn`, and the `HistoryScreen`
 * live elsewhere (tasks 12.2 and 12.3); this file holds only the small, independently-testable
 * building blocks: the per-row [SyncIndicator], the [TransactionRow], the [SummaryCard], and the
 * [EmptyState]/[ErrorState] placeholders.
 */

// -----------------------------------------------------------------------------
// Test tags — exposed so the later Compose UI tests (task 12.4) can locate nodes.
// -----------------------------------------------------------------------------

/** Test tag applied to the [SyncIndicator] when it renders the SYNCED state (Requirement 11.1). */
const val SYNC_INDICATOR_SYNCED_TAG: String = "sync_indicator_synced"

/** Test tag applied to the [SyncIndicator] when it renders the PENDING state (Requirement 11.2). */
const val SYNC_INDICATOR_PENDING_TAG: String = "sync_indicator_pending"

/** Content description announced for a SYNCED row (Requirement 11.1). */
const val SYNC_INDICATOR_SYNCED_DESCRIPTION: String = "Synced"

/** Content description announced for a PENDING row (Requirement 11.2). */
const val SYNC_INDICATOR_PENDING_DESCRIPTION: String = "Pending sync"

/** Test tag applied to a [TransactionRow]; the row id is appended so each row is addressable. */
const val TRANSACTION_ROW_TAG_PREFIX: String = "transaction_row_"

/** Test tag applied to the [SummaryCard]. */
const val SUMMARY_CARD_TAG: String = "summary_card"

/** Test tag applied to the income value inside the [SummaryCard] (Requirement 10.2). */
const val SUMMARY_INCOME_TAG: String = "summary_income"

/** Test tag applied to the expense value inside the [SummaryCard] (Requirement 10.2). */
const val SUMMARY_EXPENSE_TAG: String = "summary_expense"

/** Test tag applied to the net value inside the [SummaryCard] (Requirement 10.3). */
const val SUMMARY_NET_TAG: String = "summary_net"

/** Test tag applied to the [EmptyState] container (Requirement 1.6). */
const val EMPTY_STATE_TAG: String = "empty_state"

/** Test tag applied to the [ErrorState] container (Requirement 1.7). */
const val ERROR_STATE_TAG: String = "error_state"

// -----------------------------------------------------------------------------
// Sync indicator
// -----------------------------------------------------------------------------

/**
 * A minimal, visually-distinct per-row indicator of a transaction's [SyncStatus]
 * (Requirements 11.1, 11.2).
 *
 * The two states use different shapes AND different content descriptions so they are both
 * accessible and testable:
 * - [SyncStatus.SYNCED] draws a filled check mark (primary accent) and announces
 *   [SYNC_INDICATOR_SYNCED_DESCRIPTION].
 * - [SyncStatus.PENDING] draws a hollow ring (muted outline) and announces
 *   [SYNC_INDICATOR_PENDING_DESCRIPTION].
 *
 * Drawn with [Canvas] rather than a font icon so it renders identically without depending on the
 * extended Material icon set. Any non-0/1 `is_synced` value is already mapped to PENDING upstream
 * by the row mapper (Requirement 11.4).
 */
@Composable
fun SyncIndicator(
    status: SyncStatus,
    modifier: Modifier = Modifier
) {
    val syncedColor = MaterialTheme.colorScheme.primary
    val pendingColor = MaterialTheme.colorScheme.onSurfaceVariant

    val tag: String
    val description: String
    when (status) {
        SyncStatus.SYNCED -> {
            tag = SYNC_INDICATOR_SYNCED_TAG
            description = SYNC_INDICATOR_SYNCED_DESCRIPTION
        }
        SyncStatus.PENDING -> {
            tag = SYNC_INDICATOR_PENDING_TAG
            description = SYNC_INDICATOR_PENDING_DESCRIPTION
        }
    }

    Canvas(
        modifier = modifier
            .size(16.dp)
            .testTag(tag)
            // Collapse child drawing semantics and expose a single description for screen readers.
            .clearAndSetSemantics { contentDescription = description }
    ) {
        val w = size.width
        val h = size.height
        when (status) {
            SyncStatus.SYNCED -> {
                // A check mark: down-stroke to the low point, then up to the right.
                val strokeWidth = w * 0.14f
                val path = Path().apply {
                    moveTo(w * 0.20f, h * 0.55f)
                    lineTo(w * 0.42f, h * 0.75f)
                    lineTo(w * 0.80f, h * 0.28f)
                }
                drawPath(
                    path = path,
                    color = syncedColor,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            SyncStatus.PENDING -> {
                // A hollow ring: a distinct, unfilled shape versus the check.
                val strokeWidth = w * 0.14f
                val radius = (w / 2f) - strokeWidth
                drawCircle(
                    color = pendingColor,
                    radius = radius,
                    center = Offset(w / 2f, h / 2f),
                    style = Stroke(width = strokeWidth)
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Transaction row
// -----------------------------------------------------------------------------

/**
 * Renders a single [TransactionRowUi] as a card entry in the Transaction_List.
 *
 * Shows the transaction type, the source wallet, the category, and a human-readable date-time
 * (Requirement 1.2); the destination wallet is shown only for [TransactionType.TRANSFER]
 * (Requirement 1.3). The amount is rendered as an integer in the smallest currency unit via
 * [formatAmount] (Requirement 1.4). The trailing [SyncIndicator] reflects the row's sync status
 * (Requirements 11.1, 11.2).
 *
 * Stateless: the entire appearance is derived from [row].
 */
@Composable
fun TransactionRow(
    row: TransactionRowUi,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag(TRANSACTION_ROW_TAG_PREFIX + row.id)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Leading block: type, wallet routing, category.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = row.type.displayLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = walletRoutingLabel(row),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.categoryName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Trailing block: amount, date-time, sync indicator.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Rp ${formatAmount(row.amount)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formatDateTime(row.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SyncIndicator(status = row.syncStatus)
                }
            }
        }
    }
}

/**
 * The wallet routing line for a row: the source wallet alone for EXPENSE/INCOME, and
 * "source → destination" for a TRANSFER (Requirement 1.3). Falls back to just the source when a
 * TRANSFER unexpectedly lacks a destination name.
 */
private fun walletRoutingLabel(row: TransactionRowUi): String {
    val dest = row.destWalletName
    return if (row.type == TransactionType.TRANSFER && dest != null) {
        "${row.sourceWalletName} \u2192 $dest"
    } else {
        row.sourceWalletName
    }
}

// -----------------------------------------------------------------------------
// Summary card
// -----------------------------------------------------------------------------

/**
 * Displays the aggregate [SummaryTotals] over the current Filtered_Set: income, expense, and net
 * shown as three separate integer values in the smallest currency unit (Requirements 10.2, 10.3,
 * 1.4). When the filtered set is empty the totals are zero and render as `Rp 0` (Requirement 10.7).
 *
 * Stateless: the entire card is derived from [totals].
 */
@Composable
fun SummaryCard(
    totals: SummaryTotals,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag(SUMMARY_CARD_TAG)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SummaryColumn(
                label = "Income",
                amount = totals.incomeTotal,
                valueColor = MaterialTheme.colorScheme.primary,
                valueTag = SUMMARY_INCOME_TAG
            )
            SummaryColumn(
                label = "Expense",
                amount = totals.expenseTotal,
                valueColor = MaterialTheme.colorScheme.error,
                valueTag = SUMMARY_EXPENSE_TAG
            )
            SummaryColumn(
                label = "Net",
                amount = totals.netTotal,
                valueColor = MaterialTheme.colorScheme.onSurface,
                valueTag = SUMMARY_NET_TAG
            )
        }
    }
}

@Composable
private fun SummaryColumn(
    label: String,
    amount: Long,
    valueColor: androidx.compose.ui.graphics.Color,
    valueTag: String
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = formatSignedAmount(amount),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            modifier = Modifier.testTag(valueTag)
        )
    }
}

// -----------------------------------------------------------------------------
// Empty / error states
// -----------------------------------------------------------------------------

/**
 * Placeholder shown in place of the Transaction_List when the Filtered_Set is empty
 * (Requirement 1.6). [reason] communicates why nothing is shown (e.g. no records at all versus no
 * records matching the active filters); the active chip selections are retained by the caller.
 */
@Composable
fun EmptyState(
    reason: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp)
            .testTag(EMPTY_STATE_TAG),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Placeholder shown when a read from the Transactions_Store fails (Requirement 1.7). It signals the
 * error to the user; the caller keeps the previously displayed list state intact rather than
 * discarding it (Requirements 1.7, 12.4).
 */
@Composable
fun ErrorState(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp)
            .testTag(ERROR_STATE_TAG),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Couldn't load your transactions. Showing the last available view.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// -----------------------------------------------------------------------------
// Formatting helpers
// -----------------------------------------------------------------------------

/** Human-readable transaction-type label, consistent with the input screen's wording. */
private fun TransactionType.displayLabel(): String = when (this) {
    TransactionType.EXPENSE -> "Expense"
    TransactionType.INCOME -> "Income"
    TransactionType.TRANSFER -> "Transfer"
}

/**
 * Formats a (possibly negative) smallest-unit amount as a grouped integer with an "Rp" prefix,
 * preserving a leading minus sign for a negative net (Requirement 10.3). Reuses the input screen's
 * grouping via [formatAmount].
 */
private fun formatSignedAmount(amount: Long): String {
    val sign = if (amount < 0L) "-" else ""
    val magnitude = if (amount == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(amount)
    return "${sign}Rp ${formatAmount(magnitude)}"
}

/** Renders an epoch-millisecond timestamp as a short, locale-aware date-time (Requirement 1.2). */
private fun formatDateTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
