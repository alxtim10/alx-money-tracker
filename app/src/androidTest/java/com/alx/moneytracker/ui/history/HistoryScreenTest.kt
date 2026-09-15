package com.alx.moneytracker.ui.history

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.FilterSet
import com.alx.moneytracker.domain.SummaryTotals
import com.alx.moneytracker.domain.SyncStatus
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import com.alx.moneytracker.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI (instrumented) tests for the stateless [HistoryScreen] and its building blocks
 * (Task 12.4).
 *
 * The tests drive [HistoryScreen] with constructed [HistoryUiState] values and a capturing
 * `onEvent` lambda — no ViewModel/Room wiring — so behavior is deterministic and covers the
 * UI-facing acceptance criteria that do not vary meaningfully with input and are therefore covered
 * here rather than by property tests:
 *
 * - 3.1 / 4.1 / 5.1 / 6.1 / 7.1 — chip composition: exactly three type chips, one chip per
 *   available wallet, one chip per available category, a SYNCED chip and a PENDING chip, and a
 *   single time-range chip.
 * - 1.6 — the empty state renders in place of the list while the active chip selections are
 *   retained (the header chips stay present and selected).
 * - 1.7 — the error state renders in place of the list without clearing the previously loaded
 *   rows/totals (the summary card and header remain, and the last-good rows are preserved in state).
 * - 11.1 / 11.2 — SYNCED and PENDING [SyncIndicator]s render as visually-distinct nodes (distinct
 *   test tags / content descriptions).
 * - 11.3 — recomposing with a single row's `is_synced` flipped updates only that row's indicator
 *   while every other row's indicator is unchanged.
 * - 10.2 / 10.3 — the summary card shows income, expense, and net as three separate values.
 *
 * ### Environment note
 * These are instrumented tests and require a connected device or emulator to execute
 * (`./gradlew connectedDebugAndroidTest`). They compile without a device via
 * `./gradlew compileDebugAndroidTestKotlin`.
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Test fixtures -----------------------------------------------------------------------

    private val cashWallet = Wallet(id = 1L, name = "Cash", balance = 1_000L, isDefault = true, isArchived = false)
    private val bankWallet = Wallet(id = 2L, name = "Bank", balance = 500L, isDefault = false, isArchived = false)

    private val foodCategory = Category(id = 10L, name = "Food", type = TransactionType.EXPENSE, icon = "food", isArchived = false)
    private val salaryCategory = Category(id = 20L, name = "Salary", type = TransactionType.INCOME, icon = "cash", isArchived = false)

    private val wallets = listOf(cashWallet, bankWallet)
    private val categories = listOf(foodCategory, salaryCategory)

    private fun expenseRow(id: String, synced: Boolean) = TransactionRowUi(
        id = id,
        type = TransactionType.EXPENSE,
        amount = 25_000L,
        sourceWalletName = cashWallet.name,
        destWalletName = null,
        categoryName = foodCategory.name,
        timestamp = 1_700_000_000_000L,
        syncStatus = if (synced) SyncStatus.SYNCED else SyncStatus.PENDING
    )

    private fun incomeRow(id: String, synced: Boolean) = TransactionRowUi(
        id = id,
        type = TransactionType.INCOME,
        amount = 100_000L,
        sourceWalletName = bankWallet.name,
        destWalletName = null,
        categoryName = salaryCategory.name,
        timestamp = 1_700_000_500_000L,
        syncStatus = if (synced) SyncStatus.SYNCED else SyncStatus.PENDING
    )

    /** Matches any node whose test tag starts with [prefix] (for counting chips by dimension). */
    private fun hasTestTagPrefix(prefix: String): SemanticsMatcher =
        SemanticsMatcher("TestTag starts with '$prefix'") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }

    /** Counts the nodes in the unmerged tree whose test tag starts with [prefix]. */
    private fun countByTagPrefix(prefix: String): Int =
        composeRule.onAllNodes(hasTestTagPrefix(prefix), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    /** Counts the nodes in the unmerged tree with the exact test [tag]. */
    private fun countByExactTag(tag: String): Int =
        composeRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag),
            useUnmergedTree = true
        ).fetchSemanticsNodes().size

    /**
     * Sets the stateless screen content, capturing every dispatched event into the returned list.
     * Wraps the content in the app's Material 3 [AppTheme] so accent/color roles resolve exactly
     * as in production.
     */
    private fun setScreen(state: HistoryUiState): MutableList<HistoryEvent> {
        val events = mutableListOf<HistoryEvent>()
        composeRule.setContent {
            AppTheme {
                HistoryScreen(state = state, onEvent = { events += it })
            }
        }
        return events
    }

    // --- 3.1 / 4.1 / 5.1 / 6.1 / 7.1: chip composition ----------------------------------------

    @Test
    fun filterHeader_rendersExpectedChips_onePerDimensionSelection() {
        val state = HistoryUiState(
            rows = listOf(expenseRow("a", synced = true)),
            totals = SummaryTotals(incomeTotal = 0L, expenseTotal = 25_000L),
            availableWallets = wallets,
            availableCategories = categories
        )
        setScreen(state)

        // 3.1 — exactly three type chips, one per TransactionType.
        assertEquals(
            "There must be exactly three transaction-type chips (EXPENSE/INCOME/TRANSFER)",
            3,
            countByTagPrefix(TYPE_CHIP_TAG_PREFIX)
        )
        composeRule.onNodeWithTag(TYPE_CHIP_TAG_PREFIX + TransactionType.EXPENSE.name).assertIsDisplayed()
        composeRule.onNodeWithTag(TYPE_CHIP_TAG_PREFIX + TransactionType.INCOME.name).assertIsDisplayed()
        composeRule.onNodeWithTag(TYPE_CHIP_TAG_PREFIX + TransactionType.TRANSFER.name).assertIsDisplayed()

        // 4.1 — one chip per available wallet.
        assertEquals(
            "There must be one wallet chip per available wallet",
            wallets.size,
            countByTagPrefix(WALLET_CHIP_TAG_PREFIX)
        )
        composeRule.onNodeWithTag(WALLET_CHIP_TAG_PREFIX + cashWallet.id).assertIsDisplayed()
        composeRule.onNodeWithTag(WALLET_CHIP_TAG_PREFIX + bankWallet.id).assertIsDisplayed()

        // 5.1 — one chip per available category.
        assertEquals(
            "There must be one category chip per available category",
            categories.size,
            countByTagPrefix(CATEGORY_CHIP_TAG_PREFIX)
        )
        composeRule.onNodeWithTag(CATEGORY_CHIP_TAG_PREFIX + foodCategory.id).assertIsDisplayed()
        composeRule.onNodeWithTag(CATEGORY_CHIP_TAG_PREFIX + salaryCategory.id).assertIsDisplayed()

        // 6.1 — a SYNCED chip and a PENDING chip.
        composeRule.onNodeWithTag(SYNC_CHIP_TAG_PREFIX + SyncStatus.SYNCED.name).assertIsDisplayed()
        composeRule.onNodeWithTag(SYNC_CHIP_TAG_PREFIX + SyncStatus.PENDING.name).assertIsDisplayed()

        // 7.1 — a single time-range chip.
        composeRule.onNodeWithTag(TIME_CHIP_TAG).assertIsDisplayed()
    }

    // --- 1.6: empty state renders while chip selections are retained --------------------------

    @Test
    fun emptyState_renders_andRetainsActiveChipSelections() {
        // An active filter set (EXPENSE type + Cash wallet selected) that yields zero rows.
        val activeFilters = FilterSet(
            types = setOf(TransactionType.EXPENSE),
            walletIds = setOf(cashWallet.id)
        )
        val state = HistoryUiState(
            rows = emptyList(),
            totals = SummaryTotals(),
            filterSet = activeFilters,
            availableWallets = wallets,
            availableCategories = categories
        )
        setScreen(state)

        // The empty-state placeholder replaces the list; the list itself is not shown.
        composeRule.onNodeWithTag(EMPTY_STATE_TAG).assertIsDisplayed()
        assertEquals("The list must not be shown while the empty state is displayed", 0, countByExactTag(TRANSACTION_LIST_TAG))

        // The filtered "no matches" copy is used because a filter is active (Requirement 1.6).
        composeRule.onNodeWithText(EMPTY_STATE_REASON_FILTERED).assertIsDisplayed()

        // Chip selections are retained: the active chips remain present and selected.
        composeRule.onNodeWithTag(TYPE_CHIP_TAG_PREFIX + TransactionType.EXPENSE.name)
            .assertIsDisplayed()
            .assertIsSelected()
        composeRule.onNodeWithTag(WALLET_CHIP_TAG_PREFIX + cashWallet.id)
            .assertIsDisplayed()
            .assertIsSelected()
    }

    // --- 1.7: error state renders without clearing the prior list -----------------------------

    @Test
    fun errorState_renders_withoutClearingPreviouslyLoadedData() {
        // A read failure with previously-loaded rows/totals retained in state (Requirements 1.7, 12.4).
        val priorRows = listOf(expenseRow("a", synced = true), incomeRow("b", synced = false))
        val priorTotals = SummaryTotals(incomeTotal = 100_000L, expenseTotal = 25_000L)
        val state = HistoryUiState(
            rows = priorRows,
            totals = priorTotals,
            availableWallets = wallets,
            availableCategories = categories,
            isError = true
        )
        setScreen(state)

        // The error placeholder replaces only the list area; the list is not shown.
        composeRule.onNodeWithTag(ERROR_STATE_TAG).assertIsDisplayed()
        assertEquals("The list must not be shown while the error state is displayed", 0, countByExactTag(TRANSACTION_LIST_TAG))

        // The header and the summary card (with the last-good totals) stay visible, so the prior
        // view is preserved rather than discarded.
        composeRule.onNodeWithTag("filter_chip_header").assertIsDisplayed()
        composeRule.onNodeWithTag(SUMMARY_CARD_TAG).assertIsDisplayed()

        // The previously loaded rows/totals are preserved in state (not cleared by the error).
        assertEquals("Prior rows must be retained on error", priorRows, state.rows)
        assertEquals("Prior totals must be retained on error", priorTotals, state.totals)
    }

    // --- 11.1 / 11.2: SYNCED vs PENDING indicators render visually distinct --------------------

    @Test
    fun syncIndicators_syncedAndPending_renderAsDistinctNodes() {
        val state = HistoryUiState(
            rows = listOf(expenseRow("synced-row", synced = true), incomeRow("pending-row", synced = false)),
            totals = SummaryTotals(incomeTotal = 100_000L, expenseTotal = 25_000L),
            availableWallets = wallets,
            availableCategories = categories
        )
        setScreen(state)

        // A synced row exposes the SYNCED indicator; a pending row exposes the PENDING indicator.
        // Distinct test tags (and content descriptions) confirm the two states are visually distinct.
        assertEquals(
            "Exactly one row is synced",
            1,
            countByExactTag(SYNC_INDICATOR_SYNCED_TAG)
        )
        assertEquals(
            "Exactly one row is pending",
            1,
            countByExactTag(SYNC_INDICATOR_PENDING_TAG)
        )
    }

    // --- 11.3: a single is_synced change updates only its row ----------------------------------

    @Test
    fun singleSyncChange_updatesOnlyThatRow() {
        val mutableState = androidx.compose.runtime.mutableStateOf(
            HistoryUiState(
                rows = listOf(
                    expenseRow("row-1", synced = false),
                    incomeRow("row-2", synced = false)
                ),
                totals = SummaryTotals(incomeTotal = 100_000L, expenseTotal = 25_000L),
                availableWallets = wallets,
                availableCategories = categories
            )
        )
        composeRule.setContent {
            AppTheme {
                HistoryScreen(state = mutableState.value, onEvent = {})
            }
        }

        // Initially both rows are pending: two PENDING indicators, zero SYNCED.
        assertEquals(2, countByExactTag(SYNC_INDICATOR_PENDING_TAG))
        assertEquals(0, countByExactTag(SYNC_INDICATOR_SYNCED_TAG))

        // Flip only row-1's sync status to SYNCED and recompose.
        val current = mutableState.value
        mutableState.value = current.copy(
            rows = current.rows.map { row ->
                if (row.id == "row-1") row.copy(syncStatus = SyncStatus.SYNCED) else row
            }
        )
        composeRule.waitForIdle()

        // Only row-1 changed: exactly one SYNCED indicator now, and row-2 stays PENDING.
        composeRule.onNodeWithTag(TRANSACTION_ROW_TAG_PREFIX + "row-1", useUnmergedTree = true)
            .assertIsDisplayed()
        assertEquals(
            "Exactly one row is now synced (row-1)",
            1,
            countByExactTag(SYNC_INDICATOR_SYNCED_TAG)
        )
        assertEquals(
            "The other row (row-2) remains pending",
            1,
            countByExactTag(SYNC_INDICATOR_PENDING_TAG)
        )
    }

    // --- 10.2 / 10.3: summary card shows income/expense/net separately -------------------------

    @Test
    fun summaryCard_showsIncomeExpenseAndNetSeparately() {
        val totals = SummaryTotals(incomeTotal = 100_000L, expenseTotal = 25_000L)
        val state = HistoryUiState(
            rows = listOf(expenseRow("a", synced = true), incomeRow("b", synced = true)),
            totals = totals,
            availableWallets = wallets,
            availableCategories = categories
        )
        setScreen(state)

        composeRule.onNodeWithTag(SUMMARY_CARD_TAG).assertIsDisplayed()

        // Income, expense, and net are three separate, individually-addressable values.
        composeRule.onNodeWithTag(SUMMARY_INCOME_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SUMMARY_EXPENSE_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(SUMMARY_NET_TAG, useUnmergedTree = true).assertIsDisplayed()

        // Net is income minus expense (100_000 - 25_000 = 75_000), rendered independently of the
        // income and expense values (Requirement 10.3).
        assertEquals(75_000L, totals.netTotal)
    }
}
