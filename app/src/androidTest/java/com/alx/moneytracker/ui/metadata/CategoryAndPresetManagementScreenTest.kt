package com.alx.moneytracker.ui.metadata

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.TransactionType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI (instrumented) tests for the Scope 3 metadata management screens (Task 13.3):
 * [CategoryManagementScreen] and [QuickPresetManagementScreen].
 *
 * Both tests drive the STATELESS overloads with a controlled UiState and a capturing `onEvent`
 * lambda — no ViewModel / Room wiring — so behavior is deterministic. They cover the UI-facing
 * acceptance criteria that do not vary meaningfully with input and are therefore verified here
 * rather than by property tests:
 *
 * - 8.1 — active categories render partitioned into INCOME and EXPENSE groups.
 * - 7.3 — the category type control offers only INCOME and EXPENSE; TRANSFER is **never**
 *   selectable (there is no `category_type_TRANSFER` node, by construction of the screen).
 * - 12.1 — the preset list renders each preset's amount and label.
 * - 12.3 — the preset amount is entered through the reused
 *   [com.alx.moneytracker.ui.input.components.CustomNumpad] over a **non-focusable** amount display
 *   (a plain `Text`, never a `TextField`), so the OS keyboard is never requested for numeric entry
 *   (AGENTS.md UI Rule 1).
 * - 14.1 — tapping a preset row's Delete action dispatches
 *   [QuickPresetManagementEvent.DeletePreset] with the row's id.
 *
 * ### Environment note
 * These are instrumented tests and require a connected device or emulator to execute
 * (`./gradlew connectedDebugAndroidTest`). They compile without a device via
 * `./gradlew :app:compileDebugAndroidTestSources`.
 */
@RunWith(AndroidJUnit4::class)
class CategoryAndPresetManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Category fixtures --------------------------------------------------------------------

    private val salary = Category(id = 20L, name = "Salary", type = TransactionType.INCOME, icon = "💰", isArchived = false)
    private val bonus = Category(id = 21L, name = "Bonus", type = TransactionType.INCOME, icon = "", isArchived = false)
    private val food = Category(id = 10L, name = "Makan", type = TransactionType.EXPENSE, icon = "🍔", isArchived = false)
    private val transport = Category(id = 11L, name = "Transport", type = TransactionType.EXPENSE, icon = "🚗", isArchived = false)

    private fun categoryState() = CategoryManagementUiState(
        income = listOf(salary, bonus),
        expense = listOf(food, transport)
    )

    private fun setCategoryScreen(
        state: CategoryManagementUiState
    ): MutableList<CategoryManagementEvent> {
        val events = mutableListOf<CategoryManagementEvent>()
        composeRule.setContent {
            CategoryManagementScreen(state = state, onEvent = { events += it })
        }
        return events
    }

    // --- Preset fixtures ----------------------------------------------------------------------

    private val preset50k = QuickPreset(id = 100L, amount = 50_000L, label = "Lunch")
    private val preset10k = QuickPreset(id = 101L, amount = 10_000L, label = "Coffee")

    private fun presetState() = QuickPresetManagementUiState(
        presets = listOf(preset50k, preset10k)
    )

    private fun setPresetScreen(
        state: QuickPresetManagementUiState
    ): MutableList<QuickPresetManagementEvent> {
        val events = mutableListOf<QuickPresetManagementEvent>()
        composeRule.setContent {
            QuickPresetManagementScreen(state = state, onEvent = { events += it })
        }
        return events
    }

    // --- 8.1: categories render in INCOME and EXPENSE groups ----------------------------------

    @Test
    fun categories_renderInIncomeAndExpenseGroups() {
        setCategoryScreen(categoryState())

        // Both group headers are present within the grouped list.
        composeRule.onNodeWithTag("category_list").assertIsDisplayed()
        composeRule.onNodeWithTag("group_income").assertIsDisplayed()
        composeRule.onNodeWithTag("group_expense").assertIsDisplayed()

        // Each category is rendered as a row keyed by its id, in its correct group.
        composeRule.onNodeWithTag("category_row_${salary.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("category_row_${bonus.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("category_row_${food.id}").assertIsDisplayed()
        composeRule.onNodeWithTag("category_row_${transport.id}").assertIsDisplayed()
    }

    // --- 7.3: the type control offers only INCOME/EXPENSE; TRANSFER is never selectable -------

    @Test
    fun categoryTypeSelector_offersOnlyIncomeAndExpense() {
        setCategoryScreen(categoryState())

        // The single-choice type control and both permitted options exist.
        composeRule.onNodeWithTag("category_type_selector").assertIsDisplayed()
        composeRule.onNodeWithTag("category_type_${TransactionType.INCOME.name}").assertIsDisplayed()
        composeRule.onNodeWithTag("category_type_${TransactionType.EXPENSE.name}").assertIsDisplayed()

        // TRANSFER is NEVER offered as a category type (Requirement 7.3): no such node exists.
        composeRule.onNodeWithTag("category_type_${TransactionType.TRANSFER.name}").assertDoesNotExist()
    }

    // --- 12.1: the preset list renders amount and label ---------------------------------------

    @Test
    fun presetList_rendersAmountAndLabel() {
        setPresetScreen(presetState())

        composeRule.onNodeWithTag("preset_list").assertIsDisplayed()

        // Each preset row exists and shows its formatted amount and its label. The row's Text
        // children are merged into the row node's semantics, so assertTextContains matches either.
        composeRule.onNodeWithTag("preset_row_${preset50k.id}")
            .assertIsDisplayed()
            .assertTextContains("Rp 50.000")
            .assertTextContains(preset50k.label)

        composeRule.onNodeWithTag("preset_row_${preset10k.id}")
            .assertIsDisplayed()
            .assertTextContains("Rp 10.000")
            .assertTextContains(preset10k.label)
    }

    // --- 12.3: the preset amount uses the numpad over a non-focusable display -----------------

    @Test
    fun presetAmount_usesNumpadWithNonFocusableDisplay() {
        setPresetScreen(presetState())

        // The reused CustomNumpad drives numeric entry.
        composeRule.onNodeWithTag("numpad").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad_key_5").assertIsDisplayed()
        composeRule.onNodeWithTag("numpad_delete").assertIsDisplayed()

        // The amount display is a plain, non-focusable Text — never a TextField — so it exposes no
        // text-input semantics and cannot raise the OS keyboard for numbers (AGENTS.md UI Rule 1).
        val display = composeRule.onNodeWithTag("preset_amount_display").assertIsDisplayed()
            .fetchSemanticsNode()
        val textInputActions = display.config.filter {
            it.key.name == "SetText" || it.key.name == "InsertTextAtCursor"
        }
        assertTrue(
            "preset_amount_display must not expose text-input semantics (it is a read-only Text)",
            textInputActions.isEmpty()
        )
    }

    @Test
    fun numpadDigit_updatesNonFocusableAmountDisplay() {
        setPresetScreen(presetState())

        // Tapping numpad digits drives the display (the display starts at "Rp 0" in create mode).
        composeRule.onNodeWithTag("preset_amount_display").assertTextContains("Rp 0")
        composeRule.onNodeWithTag("numpad_key_7").performClick()
        composeRule.onNodeWithTag("numpad_key_5").performClick()

        composeRule.onNodeWithTag("preset_amount_display").assertTextContains("Rp 75")
    }

    // --- 14.1: tapping Delete dispatches DeletePreset -----------------------------------------

    @Test
    fun tappingDelete_dispatchesDeletePreset() {
        val events = setPresetScreen(presetState())

        composeRule.onNodeWithTag("preset_delete_${preset50k.id}").performClick()

        assertTrue(
            "Tapping a preset's Delete action should dispatch DeletePreset with its id",
            events.contains(QuickPresetManagementEvent.DeletePreset(preset50k.id))
        )
    }
}
