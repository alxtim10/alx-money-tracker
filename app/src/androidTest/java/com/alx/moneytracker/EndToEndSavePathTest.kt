package com.alx.moneytracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.repository.RoomTransactionRepository
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.ui.input.TransactionInputEvent
import com.alx.moneytracker.ui.input.TransactionInputViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * End-to-end integration test for the save path (Task 9.2).
 *
 * This wires the *real* collaborators together — a real in-memory Room [AppDatabase], the real
 * [RoomTransactionRepository], and the real [TransactionInputViewModel] — and drives the ViewModel
 * exactly as the UI would: through [TransactionInputViewModel.onEvent]. It asserts that a valid
 * submit walks the whole stack and produces the persisted + observable + reset outcomes the design
 * promises. No fakes or mocks are used.
 *
 * Covered acceptance criteria:
 * - 9.1  — a valid submit persists the transaction and applies the source balance change atomically.
 * - 12.1 — the default wallet is loaded into state as the source through the observable stream
 *          (auto-selected on load), and the persisted row records that source wallet.
 * - 12.2 — after the save, [RoomTransactionRepository.observeWallets] emits the updated balance.
 * - 12.3 — a successful save resets the running amount to 0 and clears the selected category.
 *
 * ### Coroutine setup
 * The ViewModel launches its Flow collectors and its submit work in `viewModelScope`, which is
 * backed by [Dispatchers.Main]. On the instrumentation test thread there is no Android main
 * dispatcher installed by default, so we install an [UnconfinedTestDispatcher] as Main for the
 * duration of each test. `Unconfined` runs launched work eagerly on the current thread, so the
 * `init {}` collectors populate state and `Submit` completes its persistence before we assert —
 * without needing to advance a virtual clock. The repository still switches to a real IO dispatcher
 * internally; that suspending call resumes deterministically under `runTest`.
 *
 * The database is built with [androidx.room.RoomDatabase.Builder.allowMainThreadQueries] so the
 * repository's DAO calls (which land on the Unconfined test thread) do not trip Room's main-thread
 * guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EndToEndSavePathTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomTransactionRepository

    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val fixedId = "e2e-id"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTransactionRepository(
            transactionDao = db.transactionDao(),
            walletDao = db.walletDao(),
            categoryDao = db.categoryDao(),
            quickPresetDao = db.quickPresetDao(),
            // Keep the atomic write on the same (test) thread so it completes deterministically.
            ioDispatcher = UnconfinedTestDispatcher()
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /** Seeds a wallet via raw SQL and returns its generated row id. */
    private fun insertWallet(
        name: String,
        balance: Long,
        isDefault: Boolean = false,
        isArchived: Boolean = false
    ): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO wallets (name, balance, is_default, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, balance, if (isDefault) 1 else 0, if (isArchived) 1 else 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /** Seeds a category via raw SQL and returns its generated row id. */
    private fun insertCategory(
        name: String,
        type: TransactionType,
        icon: String = "icon",
        isArchived: Boolean = false
    ): Long {
        val helper = db.openHelper.writableDatabase
        helper.execSQL(
            "INSERT INTO categories (name, type, icon, is_archived) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(name, type.name, icon, if (isArchived) 1 else 0)
        )
        helper.query("SELECT last_insert_rowid()").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    private fun readBalance(walletId: Long): Long? {
        db.openHelper.writableDatabase
            .query("SELECT balance FROM wallets WHERE id = ?", arrayOf<Any>(walletId))
            .use { cursor -> return if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun transactionCount(): Long {
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM transactions").use { cursor ->
            cursor.moveToFirst()
            return cursor.getLong(0)
        }
    }

    /**
     * A valid EXPENSE entry, driven purely through ViewModel events, persists one unsynced
     * transaction, decreases the source wallet balance atomically, emits the new balance through the
     * observable stream, and resets amount + category for the next entry.
     */
    @Test
    fun validExpenseSubmit_persists_updatesBalance_emitsFlow_andResets() = runTest {
        val walletId = insertWallet(name = "Cash", balance = 1_000L, isDefault = true)
        val categoryId = insertCategory(name = "Makan", type = TransactionType.EXPENSE)

        val viewModel = TransactionInputViewModel(
            repository = repository,
            clock = fixedClock,
            idGenerator = { fixedId }
        )

        // 12.1 — the default wallet is auto-loaded as the source through the observable stream.
        assertEquals(walletId, viewModel.uiState.value.sourceWalletId)

        // Compose a positive amount (300) via the numpad and select a category.
        viewModel.onEvent(TransactionInputEvent.DigitPressed(3))
        viewModel.onEvent(TransactionInputEvent.DigitPressed(0))
        viewModel.onEvent(TransactionInputEvent.DigitPressed(0))
        viewModel.onEvent(TransactionInputEvent.CategorySelected(categoryId))

        assertEquals(300L, viewModel.uiState.value.runningAmount)
        assertTrue("Submit should be enabled for a valid entry", viewModel.uiState.value.isSubmitEnabled)

        // Act: submit the entry through the real stack.
        viewModel.onEvent(TransactionInputEvent.Submit)

        // 9.1 — exactly one transaction row was persisted with the expected fields, unsynced.
        assertEquals(1L, transactionCount())
        db.openHelper.writableDatabase
            .query(
                "SELECT amount, type, source_wallet, category_id, is_synced, dest_wallet FROM transactions WHERE id = ?",
                arrayOf<Any>(fixedId)
            )
            .use { cursor ->
                assertTrue("The persisted transaction row must exist", cursor.moveToFirst())
                assertEquals(300L, cursor.getLong(0))
                assertEquals(TransactionType.EXPENSE.name, cursor.getString(1))
                assertEquals(walletId, cursor.getLong(2))
                assertEquals(categoryId, cursor.getLong(3))
                assertEquals("EXPENSE must be persisted unsynced (is_synced = 0)", 0L, cursor.getLong(4))
                assertTrue("EXPENSE has no destination wallet", cursor.isNull(5))
            }

        // 9.1 — the source balance was reduced by the amount within the atomic write.
        assertEquals(700L, readBalance(walletId))

        // 12.2 — the observable wallet stream reflects the updated balance.
        val observedBalance = repository.observeWallets().first().single { it.id == walletId }.balance
        assertEquals(700L, observedBalance)

        // 12.3 — a successful save resets amount and clears the category, preserving type + wallet.
        val stateAfter = viewModel.uiState.value
        assertEquals(0L, stateAfter.runningAmount)
        assertNull(stateAfter.selectedCategoryId)
        assertEquals(TransactionType.EXPENSE, stateAfter.selectedType)
        assertEquals(walletId, stateAfter.sourceWalletId)
    }
}
