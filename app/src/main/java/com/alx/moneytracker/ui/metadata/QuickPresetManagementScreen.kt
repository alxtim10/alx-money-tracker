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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.ui.input.components.CustomNumpad
import com.alx.moneytracker.ui.input.components.formatAmount

/**
 * Stateless Quick Preset management screen (Scope 3, Material 3).
 *
 * Renders [QuickPresetManagementUiState] and forwards every interaction as a
 * [QuickPresetManagementEvent] via [onEvent]; it holds no business logic beyond formatting and
 * event dispatch. The list contents, the surfaced validation error, and the operation-failed
 * indication all arrive precomputed in state.
 *
 * Layout mirrors Scope 1's aesthetic (design.md): a scrollable upper region carrying the
 * [LazyColumn] of presets — each showing amount and label with per-row **Edit** and **Delete**
 * (hard delete, Requirement 14) actions — over a raised, top-rounded lower-region interaction
 * cluster that hosts the create / edit editor for one-handed thumb reach (AGENTS.md UI Rule 2).
 *
 * The amount is entered through the reused [CustomNumpad] over a **non-focusable** amount display
 * (a plain [Text], never a `TextField`), so the OS keyboard is never requested for numeric entry
 * (AGENTS.md UI Rule 1). The label is ordinary text and uses the OS keyboard.
 *
 * A surfaced [QuickPresetManagementUiState.error] (or [QuickPresetManagementUiState.operationFailed])
 * is shown once through a [SnackbarHost], then [QuickPresetManagementEvent.ErrorConsumed] is
 * dispatched so the transient indication is cleared.
 */
@Composable
fun QuickPresetManagementScreen(
    state: QuickPresetManagementUiState,
    onEvent: (QuickPresetManagementEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // The preset currently being edited (null = the editor is in "create" mode).
    var editingPresetId by remember { mutableStateOf<Long?>(null) }
    // Running amount typed on the numpad, in the smallest currency unit.
    var draftAmount by remember { mutableStateOf(0L) }
    var draftLabel by remember { mutableStateOf("") }

    // Surface and consume the transient validation / operation-failure indication.
    LaunchedEffect(state.error, state.operationFailed) {
        val message = when {
            state.error != null -> state.error.toMessage()
            state.operationFailed -> "Couldn't save the preset. Please try again."
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onEvent(QuickPresetManagementEvent.ErrorConsumed)
        }
    }

    Scaffold(
        modifier = modifier.testTag("quick_preset_management_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Upper region: the scrollable list of presets.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("preset_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.presets, key = { it.id }) { preset ->
                    PresetRow(
                        preset = preset,
                        onEdit = {
                            editingPresetId = preset.id
                            draftAmount = preset.amount
                            draftLabel = preset.label
                        },
                        onDelete = { onEvent(QuickPresetManagementEvent.DeletePreset(preset.id)) }
                    )
                }
            }

            // Lower region: the create / edit editor (numpad + label), for thumb reach (UI Rule 2).
            PresetEditor(
                editingPresetId = editingPresetId,
                amount = draftAmount,
                label = draftLabel,
                onDigit = { digit ->
                    // Append a digit, guarding against overflow of the running amount.
                    val next = draftAmount * 10 + digit
                    if (next >= draftAmount) draftAmount = next
                },
                onDelete = { draftAmount /= 10 },
                onLabelChanged = { draftLabel = it },
                onSubmit = {
                    val presetId = editingPresetId
                    if (presetId == null) {
                        onEvent(QuickPresetManagementEvent.CreatePreset(draftAmount, draftLabel))
                    } else {
                        onEvent(
                            QuickPresetManagementEvent.UpdatePreset(
                                presetId = presetId,
                                amount = draftAmount,
                                label = draftLabel
                            )
                        )
                    }
                    // Reset the editor back to "create" mode after dispatch.
                    editingPresetId = null
                    draftAmount = 0L
                    draftLabel = ""
                },
                onCancel = {
                    editingPresetId = null
                    draftAmount = 0L
                    draftLabel = ""
                }
            )
        }
    }
}

/** A single preset row showing its amount and label with per-row Edit / Delete actions. */
@Composable
private fun PresetRow(
    preset: QuickPreset,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preset_row_${preset.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Rp ${formatAmount(preset.amount)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = onEdit,
                modifier = Modifier.testTag("preset_edit_${preset.id}")
            ) {
                Text("Edit")
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("preset_delete_${preset.id}")
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * The lower-region create / edit editor: a non-focusable amount display driven by the reused
 * [CustomNumpad] (UI Rule 1), a label text field (OS keyboard), and Save / Cancel actions in the
 * lower region (UI Rule 2). When [editingPresetId] is non-null the editor is in "edit" mode.
 */
@Composable
private fun PresetEditor(
    editingPresetId: Long?,
    amount: Long,
    label: String,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onLabelChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("preset_editor"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (editingPresetId == null) "New preset" else "Edit preset",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Non-focusable amount display: a plain Text, never a TextField, so the OS keyboard is
            // never requested for numeric entry (UI Rule 1).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Rp ${formatAmount(amount)}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (amount > 0L) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("preset_amount_display")
                )
            }

            OutlinedTextField(
                value = label,
                onValueChange = onLabelChanged,
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("preset_label_field")
            )

            CustomNumpad(
                onDigit = onDigit,
                onDelete = onDelete
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (editingPresetId != null) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preset_cancel_button")
                    ) {
                        Text("Cancel")
                    }
                }
                Button(
                    onClick = onSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("preset_save_button")
                ) {
                    Text(if (editingPresetId == null) "Add preset" else "Save")
                }
            }
        }
    }
}

/** Maps a [ValidationError] to a short, user-facing message for the preset surface. */
private fun ValidationError.toMessage(): String = when (this) {
    ValidationError.AMOUNT_OUT_OF_RANGE -> "Enter an amount greater than zero."
    ValidationError.LABEL_EMPTY -> "Enter a label for the preset."
    ValidationError.LABEL_TOO_LONG -> "That label is too long."
    ValidationError.NAME_EMPTY,
    ValidationError.NAME_TOO_LONG,
    ValidationError.BALANCE_OUT_OF_RANGE,
    ValidationError.TYPE_NOT_PERMITTED -> "That preset isn't valid."
}

/**
 * Stateful overload that binds the screen to a [QuickPresetManagementViewModel].
 *
 * Collects [QuickPresetManagementViewModel.uiState] and delegates to the stateless overload,
 * routing UI events back through [QuickPresetManagementViewModel.onEvent].
 */
@Composable
fun QuickPresetManagementScreen(
    viewModel: QuickPresetManagementViewModel,
    modifier: Modifier = Modifier
) {
    val state: State<QuickPresetManagementUiState> = viewModel.uiState.collectAsState()
    QuickPresetManagementScreen(
        state = state.value,
        onEvent = viewModel::onEvent,
        modifier = modifier
    )
}
