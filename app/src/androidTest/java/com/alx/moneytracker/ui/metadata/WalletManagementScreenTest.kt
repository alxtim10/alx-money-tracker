package com.alx.moneytracker.ui.metadata

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.ui.theme.AppTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI (instrumented) tests for the stateless [WalletManagementScreen] (Task 12.2).
 *
 * These tests drive the STATELESS `WalletManagementScreen(state, onEvent)` overload with a
 * controlled [WalletManagementUiState] and a capturing `onEvent` lambda — no ViewModel/Room wiring
 * — so behavior is deterministic and covers the UI-facing acceptance criteria that do not vary
 * meaningfully with input and are therefore covered here rather than by property tests:
 *
 * - 2.1 / 2.3 — the wallet list renders one row per Active_Wallet, each showing the wallet name, its
 *   integer smallest-unit balance, and (for the default wallet) a default marker.
 * - 1.1 / 4.1 / AGENTS UI Rule 1 — opening the create editor and the per-row override editor shows a
 *   NON-FOCUSABLE amount display (a `Text`, not an editable `TextField`) driven by the in-app
 *   [com.alx.moneytracker.ui.input.components.CustomNumpad]; the numpad is present, so the OS
 *   keyboard is never requested for numeric entry.
 * - 1.2 / 3.2 / 4.2 — an inline validation error surfaces from [WalletManagementUiState.error] inside
 *   the active editor.
 *
 * ### Environment note
 * These are instrumented tests and require a connected device or emulator to execute
 * (`./gradlew connectedDebugAndroidTest`). They compile without a device via
 * `./gradlew compileDebugAndroidTestKotlin` / `:app:compileDebugAndroidTestSources`.
 */
@RunWith(AndroidJUnit4::class)
class WalletManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Test fixtures -----------------------------------------------------------------------

    private val cashWallet = Wallet(id = 1L, name = "Cash", balance = 1_234_567L, isDefault = true, isArchived = false)
    private val bankWallet = Wallet(id = 2L, name = "Bank", balance = 500L, isDefault = false, isArchived = false)

    private val wallets = listOf(cashWallet, bankWallet)

    /**
     * Sets the stateless screen content, capturing every dispatched event into the returned list.
     * Wraps the content in the app's Material 3 [AppTheme] so accent/color roles resolve exactly as
     * in production.
     */
    private fun setScreen(state: WalletManagementUiState): MutableList<WalletManagementEvent> {
        val events = mutableListOf<WalletManagementEvent>()
        composeRule.setContent {
            AppTheme {
                WalletManagementScreen(state = state, onEvent = { events += it })
            }
        }
        return events
    }

    // --- 2.1 / 2.3: the list renders name, balance, and default marker ------------------------

    @Test
    fun walletList_rendersNameBalanceAndDefaultMarker() {
        setScreen(WalletManagementUiState(wallets = wallets))

        // The list itself is shown, with one addressable row per active wallet.
        composeRule.onNodeWithTag("wallet_list").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_row_${cashWallet.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet_row_${bankWallet.id}").assertIsDisplayed()

        // Each row shows its name (2.1).
        composeRule.onNodeWithText(cashWallet.name).assertIsDisplayed()
        composeRule.onNodeWithText(bankWallet.name).assertIsDisplayed()

        // Each row shows its integer smallest-unit balance, grouped (2.3): 1234567 -> "Rp 1.234.567".
        composeRule.onNodeWithTag("wallet_balance_${cashWallet.id}", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("Rp 1.234.567")
        composeRule.onNodeWithTag("wallet_balance_${bankWallet.id}", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("Rp 500")
    }

    @Test
    fun defaultMarker_shownOnlyForDefaultWallet() {
        setScreen(WalletManagementUiState(wallets = wallets))

        // The default wallet (Cash) carries the default marker; the non-default (Bank) does not.
        composeRule.onNodeWithTag("wallet_default_marker_${cashWallet.id}", useUnmergedTree = true)
            .assertIsDisplayed()

        assertFalse(
            "The non-default wallet must not carry a default marker",
            composeRule.onAllNodesWithTag("wallet_default_marker_${bankWallet.id}", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    // --- 1.1 / 4.1 / UI Rule 1: numpad-driven, non-focusable amount display -------------------

    @Test
    fun createEditor_showsNumpadDrivenNonFocusableAmountDisplay() {
        setScreen(WalletManagementUiState(wallets = wallets))

        // Open the create editor.
        composeRule.onNodeWithTag("wallet_create_fab").assertIsDisplayed().performClick()

        // The editor panel and the in-app numpad are shown; numeric entry is numpad-only (UI Rule 1).
        composeRule.onNodeWithTag("wallet_editor_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad_key_5", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("numpad_delete", useUnmergedTree = true).assertIsDisplayed()

        // The amount display is a plain (non-focusable) Text: it exposes no text-input semantics
        // (SetText/InsertTextAtCursor) and therefore cannot raise the OS keyboard for numbers.
        assertAmountDisplayIsNotEditable()
    }

    @Test
    fun createEditor_amountDisplay_reflectsNumpadInput_withoutOsKeyboard() {
        setScreen(WalletManagementUiState(wallets = wallets))

        composeRule.onNodeWithTag("wallet_create_fab").performClick()

        // Tapping digits on the in-app numpad drives the read-only display: 1, 2, 5 -> "125".
        composeRule.onNodeWithTag("numpad_key_1", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("numpad_key_2", useUnmergedTree = true).performClick()
        composeRule.onNodeWithTag("numpad_key_5", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("wallet_amount_display", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextEquals("125")
    }

    @Test
    fun overrideEditor_showsNumpadDrivenNonFocusableAmountDisplay() {
        setScreen(WalletManagementUiState(wallets = wallets))

        // Open the per-row Override editor (Direct Balance Override, Req 4.1).
        composeRule.onNodeWithTag("wallet_override_${bankWallet.id}").assertIsDisplayed().performClick()

        composeRule.onNodeWithTag("wallet_editor_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad").assertIsDisplayed()

        // The override target is entered via the same non-focusable numpad-driven display (UI Rule 1).
        assertAmountDisplayIsNotEditable()
    }

    // --- 1.2 / 3.2 / 4.2: inline error surfaces from state.error ------------------------------

    @Test
    fun inlineError_rendersInEditor_whenStateErrorIsSet() {
        // A create submission rejected for an out-of-range balance surfaces an inline error.
        setScreen(
            WalletManagementUiState(
                wallets = wallets,
                error = ValidationError.BALANCE_OUT_OF_RANGE
            )
        )

        // Open an editor so the error region is composed.
        composeRule.onNodeWithTag("wallet_create_fab").performClick()

        composeRule.onNodeWithTag("wallet_editor_error", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun noInlineError_whenStateErrorIsNull() {
        setScreen(WalletManagementUiState(wallets = wallets, error = null))

        composeRule.onNodeWithTag("wallet_create_fab").performClick()

        assertFalse(
            "No inline error node should be present when state.error is null",
            composeRule.onAllNodesWithTag("wallet_editor_error", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    /** Asserts the numpad-driven amount display is a read-only Text (no OS keyboard for numbers). */
    private fun assertAmountDisplayIsNotEditable() {
        val node = composeRule.onNodeWithTag("wallet_amount_display", useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
        val inputActions = node.config.filter {
            it.key.name == "SetText" || it.key.name == "InsertTextAtCursor"
        }
        assertTrue(
            "wallet_amount_display must not expose text-input semantics (it is a read-only Text)",
            inputActions.isEmpty()
        )
    }
}
