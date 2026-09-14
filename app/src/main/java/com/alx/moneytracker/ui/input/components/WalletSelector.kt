package com.alx.moneytracker.ui.input.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet

/**
 * Wallet pickers for a transaction.
 *
 * - The source selector is always shown and lists every non-archived wallet (Requirement 4.2).
 * - The destination selector is shown only when [type] is TRANSFER, and excludes the currently
 *   selected source wallet so a wallet cannot transfer to itself (Requirement 4.3).
 *
 * Modern styling: soft, fully-rounded chips with the accent container when selected.
 * Stateless: active chips are driven by [sourceWalletId] / [destWalletId]; taps emit
 * [onSourceSelected] / [onDestSelected].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WalletSelector(
    wallets: List<Wallet>,
    sourceWalletId: Long?,
    destWalletId: Long?,
    type: TransactionType,
    onSourceSelected: (Long) -> Unit,
    onDestSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeWallets = wallets.filter { !it.isArchived }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            .testTag("wallet_selector"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "From",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WalletChipRow(
            wallets = activeWallets,
            selectedWalletId = sourceWalletId,
            onSelected = onSourceSelected,
            tagPrefix = "source_wallet"
        )

        if (type == TransactionType.TRANSFER) {
            Text(
                text = "To",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            WalletChipRow(
                wallets = activeWallets.filter { it.id != sourceWalletId },
                selectedWalletId = destWalletId,
                onSelected = onDestSelected,
                tagPrefix = "dest_wallet"
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WalletChipRow(
    wallets: List<Wallet>,
    selectedWalletId: Long?,
    onSelected: (Long) -> Unit,
    tagPrefix: String
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${tagPrefix}_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        wallets.forEach { wallet ->
            val selected = wallet.id == selectedWalletId
            FilterChip(
                selected = selected,
                onClick = { onSelected(wallet.id) },
                label = { Text(wallet.name, style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.testTag("${tagPrefix}_${wallet.id}")
            )
        }
    }
}
