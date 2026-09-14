package com.alx.moneytracker.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.ui.input.components.AmountDisplay
import com.alx.moneytracker.ui.input.components.CategorySelector
import com.alx.moneytracker.ui.input.components.CustomNumpad
import com.alx.moneytracker.ui.input.components.NoteField
import com.alx.moneytracker.ui.input.components.QuickPresetChips
import com.alx.moneytracker.ui.input.components.SubmitButton
import com.alx.moneytracker.ui.input.components.TransactionTypeSelector
import com.alx.moneytracker.ui.input.components.WalletSelector

/**
 * Stateless Transaction Input screen.
 *
 * Renders the child composables from [TransactionInputUiState] and forwards every interaction as a
 * [TransactionInputEvent] via [onEvent].
 *
 * Modern layout (clean + soft): a faint blue-grey background with the amount hero and grouped
 * metadata presented in rounded white cards in the scrollable upper region, and the primary
 * interaction cluster — quick-preset chips, the [CustomNumpad], and the [SubmitButton] — anchored
 * in a raised, top-rounded surface in the LOWER region for one-handed thumb reach (Requirement 8,
 * AGENTS.md UI Rule 2). The numpad is compact so the note stays reachable without scrolling.
 *
 * Errors are surfaced through a [SnackbarHost]: whenever [TransactionInputUiState.errorMessage]
 * becomes non-null it is shown once, then [TransactionInputEvent.ErrorConsumed] is dispatched so the
 * transient message is cleared (Requirement 9.5).
 */
@Composable
fun TransactionInputScreen(
    state: TransactionInputUiState,
    onEvent: (TransactionInputEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show and consume transient error messages (Requirement 9.5).
    LaunchedEffect(state.errorMessage) {
        val message = state.errorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onEvent(TransactionInputEvent.ErrorConsumed)
        }
    }

    Scaffold(
        modifier = modifier.testTag("transaction_input_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Upper region: amount hero + entry metadata, in soft rounded cards. Scrollable and
            // weighted so it yields space to the lower interaction cluster.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("upper_region"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionCard {
                    AmountDisplay(runningAmount = state.runningAmount)
                }

                SectionCard {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TransactionTypeSelector(
                            selectedType = state.selectedType,
                            onTypeSelected = { onEvent(TransactionInputEvent.TypeSelected(it)) }
                        )

                        WalletSelector(
                            wallets = state.wallets,
                            sourceWalletId = state.sourceWalletId,
                            destWalletId = state.destWalletId,
                            type = state.selectedType,
                            onSourceSelected = { onEvent(TransactionInputEvent.SourceWalletSelected(it)) },
                            onDestSelected = { onEvent(TransactionInputEvent.DestWalletSelected(it)) }
                        )

                        CategorySelector(
                            categories = state.categories,
                            selectedCategoryId = state.selectedCategoryId,
                            onCategorySelected = { onEvent(TransactionInputEvent.CategorySelected(it)) }
                        )

                        NoteField(
                            note = state.note,
                            onNoteChanged = { onEvent(TransactionInputEvent.NoteChanged(it)) }
                        )
                    }
                }
            }

            // Lower region: raised, top-rounded interaction cluster (Requirement 8, UI Rule 2).
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
                        .padding(top = 12.dp, bottom = 8.dp)
                        .testTag("lower_region"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QuickPresetChips(
                        presets = state.presets,
                        onPresetTap = { onEvent(TransactionInputEvent.PresetTapped(it)) }
                    )

                    CustomNumpad(
                        onDigit = { onEvent(TransactionInputEvent.DigitPressed(it)) },
                        onDelete = { onEvent(TransactionInputEvent.DeletePressed) }
                    )

                    SubmitButton(
                        enabled = state.isSubmitEnabled,
                        onSubmit = { onEvent(TransactionInputEvent.Submit) }
                    )
                }
            }
        }
    }
}

/** A soft, rounded white card used to group a section of the upper region. */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        content()
    }
}

/**
 * Stateful overload that binds the screen to a [TransactionInputViewModel].
 *
 * Collects [TransactionInputViewModel.uiState] and delegates to the stateless overload, routing UI
 * events back through [TransactionInputViewModel.onEvent]. Uses [collectAsState] (rather than the
 * lifecycle-aware variant) to avoid pulling in an extra dependency.
 */
@Composable
fun TransactionInputScreen(
    viewModel: TransactionInputViewModel,
    modifier: Modifier = Modifier
) {
    val state: State<TransactionInputUiState> = viewModel.uiState.collectAsState()
    TransactionInputScreen(
        state = state.value,
        onEvent = viewModel::onEvent,
        modifier = modifier
    )
}
