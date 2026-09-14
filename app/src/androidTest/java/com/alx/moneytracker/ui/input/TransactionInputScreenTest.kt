package com.alx.moneytracker.ui.input

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI (instrumented) tests for [TransactionInputScreen] (Task 8.3).
 *
 * These tests drive the STATELESS [TransactionInputScreen] overload with a controlled
 * [TransactionInputUiState] and a capturing `onEvent` lambda, so behavior is deterministic and no
 * ViewModel/Room wiring is involved. They validate the UI-facing acceptance criteria that do not
 * vary meaningfully with input and are therefore covered here rather than by property tests:
 *
 * - 1.1 — the [com.alx.moneytracker.ui.input.components.CustomNumpad] is shown and the amount field
 *   is a read-only display (a `Text`, not an editable `TextField`), so the OS keyboard is never
 *   requested for numeric entry (AGENTS.md UI Rule 1).
 * - 1.5 — the amount is rendered as a grouped integer of the smallest currency unit.
 * - 2.1 / 2.4 — the configured preset chips render and tapping one dispatches
 *   [TransactionInputEvent.PresetTapped]; the displayed amount reflects the state's `runningAmount`.
 * - 3.3 / 5.4 — the selected transaction type and category render as active (selected) chips.
 * - 6.1 / 6.2 / 6.3 — the note field is shown and, once focused, accepts free text (the practical
 *   proxy for "keyboard hidden until tapped, shown on tap": the field only takes input on focus).
 * - 8.1 / 8.2 — the numpad and submit control live in the lower region of the screen, below the
 *   upper metadata region (AGENTS.md UI Rule 2, one-handed ergonomics).
 *
 * ### Environment note
 * These are instrumented tests and require a connected device or emulator to execute
 * (`./gradlew connectedDebugAndroidTest`). They compile without a device via
 * `./gradlew compileDebugAndroidTestKotlin`.
 */
@RunWith(AndroidJUnit4::class)
class TransactionInputScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Test fixtures -----------------------------------------------------------------------

    private val cashWallet = Wallet(id = 1L, name = "Cash", balance = 1_000L, isDefault = true, isArchived = false)
    private val bankWallet = Wallet(id = 2L, name = "Bank", balance = 500L, isDefault = false, isArchived = false)

    private val foodCategory = Category(id = 10L, name = "Food", type = TransactionType.EXPENSE, icon = "food", isArchived = false)
    private val transportCategory = Category(id = 11L, name = "Transport", type = TransactionType.EXPENSE, icon = "bus", isArchived = false)
    private val salaryCategory = Category(id = 20L, name = "Salary", type = TransactionType.INCOME, icon = "cash", isArchived = false)

    private val preset10k = QuickPreset(id = 100L, amount = 10_000L, label = "+10k")
    private val preset50k = QuickPreset(id = 101L, amount = 50_000L, label = "+50k")

    /** A representative, fully populated state used by most tests. */
    private fun baseState(
        runningAmount: Long = 0L,
        selectedType: TransactionType = TransactionType.EXPENSE,
        selectedCategoryId: Long? = null,
        note: String = ""
    ) = TransactionInputUiState(
        runningAmount = runningAmount,
        selectedType = selectedType,
        sourceWalletId = cashWallet.id,
        destWalletId = null,
        selectedCategoryId = selectedCategoryId,
        note = note,
        wallets = listOf(cashWallet, bankWallet),
        categories = listOf(foodCategory, transportCategory),
        presets = listOf(preset10k, preset50k)
    )

    /**
     * Sets the stateless screen content, capturing every dispatched event into [events].
     * Returns the mutable event list so tests can assert on it after performing actions.
     */
    private fun setScreen(state: TransactionInputUiState): MutableList<TransactionInputEvent> {
        val events = mutableListOf<TransactionInputEvent>()
        composeRule.setContent {
            TransactionInputScreen(state = state, onEvent = { events += it })
        }
        return events
    }

    // --- 1.1: Numpad shown, digit keys present ------------------------------------------------

    @Test
    fun numpad_isDisplayed_withDigitAndDeleteKeys() {
        setScreen(baseState())

        composeRule.onNodeWithTag("numpad").assertIsDisplayed()
        // A representative digit key and the delete key exist.
        composeRule.onNodeWithTag("numpad_key_5").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad_key_0").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad_delete").assertIsDisplayed()
    }

    @Test
    fun numpad_digitTap_dispatchesDigitPressed() {
        val events = setScreen(baseState())

        composeRule.onNodeWithTag("numpad_key_7").performClick()

        assertTrue(
            "Tapping a digit key should dispatch DigitPressed(7)",
            events.contains(TransactionInputEvent.DigitPressed(7))
        )
    }

    // --- 1.1 / 1.5: amount display is a read-only Text (no OS keyboard for numbers) -----------

    @Test
    fun amountDisplay_isDisplayed_andRendersGroupedInteger() {
        // 1.5 — grouped integer of the smallest currency unit: 1234567 -> "1.234.567".
        setScreen(baseState(runningAmount = 1_234_567L))

        composeRule.onNodeWithTag("amount_display")
            .assertIsDisplayed()
            .assertTextEquals("1.234.567")
    }

    @Test
    fun amountDisplay_isNotAnEditableTextField() {
        // 1.1 / AGENTS UI Rule 1 — the amount field is a plain Text, so it exposes no text-input
        // semantics (SetText/InsertTextAtCursor) and cannot raise the OS keyboard for numbers.
        setScreen(baseState(runningAmount = 500L))

        val node = composeRule.onNodeWithTag("amount_display").fetchSemanticsNode()
        val actions = node.config.filter { it.key.name == "SetText" || it.key.name == "InsertTextAtCursor" }
        assertTrue(
            "amount_display must not expose text-input semantics (it is a read-only Text)",
            actions.isEmpty()
        )
    }

    // --- 2.1 / 2.4: presets render, tapping dispatches, amount reflects state ------------------

    @Test
    fun presets_render_eachChipDisplayed() {
        setScreen(baseState())

        composeRule.onNodeWithTag("quick_preset_chips").assertIsDisplayed()
        composeRule.onNodeWithTag("preset_chip_${preset10k.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("preset_chip_${preset50k.id}").assertIsDisplayed()
    }

    @Test
    fun preset_tap_dispatchesPresetTappedWithValue() {
        val events = setScreen(baseState())

        composeRule.onNodeWithTag("preset_chip_${preset50k.id}").performClick()

        assertTrue(
            "Tapping a preset chip should dispatch PresetTapped with its amount",
            events.contains(TransactionInputEvent.PresetTapped(preset50k.amount))
        )
    }

    @Test
    fun amountDisplay_reflectsRunningAmountFromState() {
        // 2.4 — the displayed amount is driven by state.runningAmount (as it would be after a
        // preset/digit event updates the ViewModel state and re-renders).
        setScreen(baseState(runningAmount = 60_000L))

        composeRule.onNodeWithTag("amount_display").assertTextEquals("60.000")
    }

    // --- 3.3: selected type renders active -----------------------------------------------------

    @Test
    fun selectedType_rendersAsActive() {
        setScreen(baseState(selectedType = TransactionType.INCOME))

        composeRule.onNodeWithTag("type_${TransactionType.INCOME.name}")
            .assertIsDisplayed()
            .assertIsSelected()
    }

    // --- 5.4: selected category shown in the dropdown anchor -----------------------------------

    @Test
    fun selectedCategory_isShownInDropdownAnchor() {
        // The category picker is an exposed dropdown; the selected category's name is shown in the
        // read-only anchor field (tagged "category_selector").
        setScreen(baseState(selectedCategoryId = transportCategory.id))

        composeRule.onNodeWithTag("category_selector")
            .assertIsDisplayed()
            .assertTextContains(transportCategory.name)
    }

    @Test
    fun categoryDropdown_opens_andSelectingItemDispatchesEvent() {
        val events = setScreen(baseState())

        // Open the dropdown by tapping the anchor, then pick a category from the menu.
        composeRule.onNodeWithTag("category_selector").performClick()
        composeRule.onNodeWithTag("category_${foodCategory.id}").assertIsDisplayed().performClick()

        assertTrue(
            "Selecting a category item should dispatch CategorySelected with its id",
            events.contains(TransactionInputEvent.CategorySelected(foodCategory.id))
        )
    }

    // --- 6.1 / 6.2 / 6.3: note field shown and accepts free text on focus ----------------------

    @Test
    fun noteField_isDisplayed_andAcceptsTextInputOnFocus() {
        val events = setScreen(baseState())

        // The note field is in the scrollable upper region and may be below the fold on smaller
        // screens; scroll it into view before asserting it is displayed.
        composeRule.onNodeWithTag("note_field").performScrollTo().assertIsDisplayed()

        // A text field requests the OS keyboard only on focus; performClick focuses it, then it
        // accepts free text. The dispatched NoteChanged event is the practical proxy for "keyboard
        // shown on tap and field accepts input" (6.1-6.3).
        composeRule.onNodeWithTag("note_field").performClick()
        composeRule.onNodeWithTag("note_field").performTextInput("lunch")

        assertTrue(
            "Typing into the focused note field should dispatch at least one NoteChanged event",
            events.any { it is TransactionInputEvent.NoteChanged }
        )
        val lastNote = events.filterIsInstance<TransactionInputEvent.NoteChanged>().last()
        assertEquals("lunch", lastNote.text)
    }

    // --- 7 sanity: submit disabled when invalid (no amount/category) ---------------------------

    @Test
    fun submitButton_isDisabled_whenEntryInvalid() {
        // amount == 0 and no category selected -> submit disabled.
        setScreen(baseState(runningAmount = 0L, selectedCategoryId = null))

        composeRule.onNodeWithTag("submit_button")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    // --- 8.1 / 8.2: numpad and submit live in the lower region ---------------------------------

    @Test
    fun lowerRegion_containsNumpadAndSubmit_belowUpperRegion() {
        setScreen(baseState())

        composeRule.onNodeWithTag("lower_region").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad").assertIsDisplayed()
        composeRule.onNodeWithTag("submit_button").assertIsDisplayed()

        val upperBounds = composeRule.onNodeWithTag("upper_region").getUnclippedBoundsInRoot()
        val lowerBounds = composeRule.onNodeWithTag("lower_region").getUnclippedBoundsInRoot()

        // The lower interaction cluster starts at or below the bottom of the upper region
        // (Requirement 8, AGENTS UI Rule 2). A small tolerance guards against sub-pixel rounding.
        assertTrue(
            "lower_region (top=${lowerBounds.top}) should be below upper_region (bottom=${upperBounds.bottom})",
            lowerBounds.top.value >= upperBounds.bottom.value - 1f
        )

        // The numpad and submit control sit within the lower region's vertical span.
        val numpadBounds = composeRule.onNodeWithTag("numpad").getUnclippedBoundsInRoot()
        val submitBounds = composeRule.onNodeWithTag("submit_button").getUnclippedBoundsInRoot()
        assertTrue(
            "numpad should sit inside the lower region",
            numpadBounds.top.value >= lowerBounds.top.value - 1f
        )
        assertTrue(
            "submit button should be at the bottom of the lower region",
            submitBounds.bottom.value <= lowerBounds.bottom.value + 1f
        )
    }
}
