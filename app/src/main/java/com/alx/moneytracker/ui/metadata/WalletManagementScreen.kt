package com.alx.moneytracker.ui.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.ui.input.components.CustomNumpad
import com.alx.moneytracker.ui.input.components.formatAmount

/**
 * Identifies which mutating editor is currently open in the wallet management screen.
 *
 * Only one editor is active at a time. [Create] and [Override] drive a numeric value entered
 * exclusively through the reused [CustomNumpad] (AGENTS.md UI Rule 1); [Create] and [Rename] use an
 * OS keyboard text field for the wallet name.
 */
private sealed interface WalletEditor {
    /** Creating a new wallet: name + optional numpad-driven initial balance. */
    data object Create : WalletEditor

    /** Renaming an existing wallet via an OS keyboard text field. */
    data class Rename(val walletId: Long, val currentName: String) : WalletEditor

    /** Direct Balance Override: a numpad-driven target balance for [walletId] (Req 4.1). */
    data class Override(val walletId: Long, val walletName: String) : WalletEditor
}

/**
 * Stateless wallet management screen (Scope 3, Material 3).
 *
 * Renders the Active_Wallet collection from [WalletManagementUiState] as a [LazyColumn] where each
 * row shows the wallet name, its integer smallest-unit balance, a default marker, and the per-row
 * actions Set default / Edit name / Override balance / Archive (Requirements 2.1, 2.3, 3.1, 4.1,
 * 5.1, 6.x). Every interaction is forwarded as a [WalletManagementEvent] via [onEvent]; the screen
 * holds no domain state of its own beyond the transient editor UI.
 *
 * Numeric entry — a new wallet's initial balance and a [WalletEditor.Override] target — uses a
 * non-focusable amount display driven by the reused [CustomNumpad], so the OS keyboard never appears
 * for numbers (Requirement 1.1, AGENTS.md UI Rule 1). The wallet name uses an ordinary OS-keyboard
 * text field. The editor (create/rename/override) surfaces in a raised, top-rounded [Surface] in the
 * LOWER region with the numpad and primary Save action, for one-handed thumb reach (UI Rule 2).
 *
 * Inline validation errors from [WalletManagementUiState.error] are rendered as text inside the
 * active editor (mapped to a human-readable message per offending field); once shown they are
 * cleared via [WalletManagementEvent.ErrorConsumed] when the editor closes.
 */
@Composable
fun WalletManagementScreen(
    state: WalletManagementUiState,
    onEvent: (WalletManagementEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Which editor (if any) is currently open. Transient UI state; the screen stays stateless w.r.t.
    // domain data.
    var editor by remember { mutableStateOf<WalletEditor?>(null) }
    // The name text field content while creating/renaming (OS keyboard).
    var nameField by remember { mutableStateOf(TextFieldValue("")) }
    // The running numeric value being typed on the numpad (smallest unit) for create/override.
    var amount by remember { mutableStateOf(0L) }

    fun closeEditor() {
        editor = null
        nameField = TextFieldValue("")
        amount = 0L
        onEvent(WalletManagementEvent.ErrorConsumed)
    }

    Scaffold(
        modifier = modifier.testTag("wallet_management_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (editor == null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        nameField = TextFieldValue("")
                        amount = 0L
                        onEvent(WalletManagementEvent.ErrorConsumed)
                        editor = WalletEditor.Create
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New wallet") },
                    modifier = Modifier.testTag("wallet_create_fab")
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Upper region: the live list of active wallets (Requirements 2.1, 2.3).
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .testTag("wallet_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                items(state.wallets, key = { it.id }) { wallet ->
                    WalletRow(
                        wallet = wallet,
                        onSetDefault = { onEvent(WalletManagementEvent.SetDefaultWallet(wallet.id)) },
                        onArchive = { onEvent(WalletManagementEvent.ArchiveWallet(wallet.id)) },
                        onEditName = {
                            nameField = TextFieldValue(wallet.name)
                            amount = 0L
                            onEvent(WalletManagementEvent.ErrorConsumed)
                            editor = WalletEditor.Rename(wallet.id, wallet.name)
                        },
                        onOverride = {
                            amount = 0L
                            nameField = TextFieldValue("")
                            onEvent(WalletManagementEvent.ErrorConsumed)
                            editor = WalletEditor.Override(wallet.id, wallet.name)
                        }
                    )
                }
            }

            // Lower region: the active editor cluster (name field / numpad / primary Save),
            // anchored low for one-handed reach (UI Rule 2).
            val currentEditor = editor
            if (currentEditor != null) {
                WalletEditorPanel(
                    editor = currentEditor,
                    nameField = nameField,
                    onNameChange = { nameField = it },
                    amount = amount,
                    onDigit = { digit -> amount = appendDigit(amount, digit) },
                    onDelete = { amount /= 10 },
                    error = state.error,
                    onCancel = { closeEditor() },
                    onSave = {
                        when (currentEditor) {
                            is WalletEditor.Create ->
                                onEvent(WalletManagementEvent.CreateWallet(nameField.text, amount))
                            is WalletEditor.Rename ->
                                onEvent(WalletManagementEvent.RenameWallet(currentEditor.walletId, nameField.text))
                            is WalletEditor.Override ->
                                onEvent(WalletManagementEvent.OverrideBalance(currentEditor.walletId, amount))
                        }
                    }
                )
            }
        }
    }
}

/** Appends a single [digit] (0-9) to the running smallest-unit [current] value, capped to avoid overflow. */
private fun appendDigit(current: Long, digit: Int): Long {
    // Guard against Long overflow; the validator enforces the real MAX_MONEY bound on submit.
    if (current > (Long.MAX_VALUE - digit) / 10) return current
    return current * 10 + digit
}

/** A single wallet row: name, balance, default marker, and per-row actions. */
@Composable
private fun WalletRow(
    wallet: Wallet,
    onSetDefault: () -> Unit,
    onArchive: () -> Unit,
    onEditName: () -> Unit,
    onOverride: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wallet_row_${wallet.id}")
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = wallet.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Rp ${formatAmount(wallet.balance)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("wallet_balance_${wallet.id}")
                    )
                }
                if (wallet.isDefault) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text("Default") },
                        modifier = Modifier.testTag("wallet_default_marker_${wallet.id}")
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!wallet.isDefault) {
                    AssistChip(
                        onClick = onSetDefault,
                        label = { Text("Set default") },
                        modifier = Modifier.testTag("wallet_set_default_${wallet.id}")
                    )
                }
                AssistChip(
                    onClick = onEditName,
                    label = { Text("Edit name") },
                    modifier = Modifier.testTag("wallet_edit_name_${wallet.id}")
                )
                AssistChip(
                    onClick = onOverride,
                    label = { Text("Override") },
                    modifier = Modifier.testTag("wallet_override_${wallet.id}")
                )
                AssistChip(
                    onClick = onArchive,
                    label = { Text("Archive") },
                    modifier = Modifier.testTag("wallet_archive_${wallet.id}")
                )
            }
        }
    }
}

/**
 * The lower-region editor panel for creating a wallet, renaming a wallet, or overriding a balance.
 *
 * For [WalletEditor.Create] and [WalletEditor.Override] the numeric value is shown in a read-only
 * (non-focusable) [Text] display and typed exclusively via [CustomNumpad] — the OS keyboard is
 * never requested for numbers (UI Rule 1). The wallet name ([WalletEditor.Create] /
 * [WalletEditor.Rename]) uses an ordinary [OutlinedTextField] with the OS keyboard.
 */
@Composable
private fun WalletEditorPanel(
    editor: WalletEditor,
    nameField: TextFieldValue,
    onNameChange: (TextFieldValue) -> Unit,
    amount: Long,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    error: ValidationError?,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val showName = editor is WalletEditor.Create || editor is WalletEditor.Rename
    val showNumpad = editor is WalletEditor.Create || editor is WalletEditor.Override

    val title = when (editor) {
        is WalletEditor.Create -> "New wallet"
        is WalletEditor.Rename -> "Edit name"
        is WalletEditor.Override -> "Override balance — ${editor.walletName}"
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wallet_editor_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (showName) {
                OutlinedTextField(
                    value = nameField,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wallet_name_field")
                )
            }

            if (showNumpad) {
                // Non-focusable amount display — numeric entry is numpad-only (UI Rule 1).
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Rp",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = formatAmount(amount),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (amount > 0L) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("wallet_amount_display")
                        )
                    }
                }
            }

            val errorMessage = error?.let(::errorMessageFor)
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("wallet_editor_error")
                )
            }

            if (showNumpad) {
                CustomNumpad(
                    onDigit = onDigit,
                    onDelete = onDelete
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.testTag("wallet_editor_cancel")
                ) {
                    Text("Cancel")
                }
                androidx.compose.material3.Button(
                    onClick = onSave,
                    modifier = Modifier.testTag("wallet_editor_save")
                ) {
                    Text("Save")
                }
            }
        }
    }
}

/** Maps a [ValidationError] to a short, human-readable inline message for the wallet screen. */
private fun errorMessageFor(error: ValidationError): String = when (error) {
    ValidationError.NAME_EMPTY -> "Name cannot be empty"
    ValidationError.NAME_TOO_LONG -> "Name is too long"
    ValidationError.BALANCE_OUT_OF_RANGE -> "Balance is out of range"
    ValidationError.AMOUNT_OUT_OF_RANGE -> "Amount is out of range"
    ValidationError.LABEL_EMPTY -> "Label cannot be empty"
    ValidationError.LABEL_TOO_LONG -> "Label is too long"
    ValidationError.TYPE_NOT_PERMITTED -> "That type is not permitted"
}

/**
 * Stateful overload that binds the screen to a [WalletManagementViewModel]: collects its
 * [WalletManagementViewModel.uiState] and routes events back through
 * [WalletManagementViewModel.onEvent].
 */
@Composable
fun WalletManagementScreen(
    viewModel: WalletManagementViewModel,
    modifier: Modifier = Modifier
) {
    val state: State<WalletManagementUiState> = viewModel.uiState.collectAsState()
    WalletManagementScreen(
        state = state.value,
        onEvent = viewModel::onEvent,
        modifier = modifier
    )
}
