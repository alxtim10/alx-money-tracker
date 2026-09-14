package com.alx.moneytracker

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alx.moneytracker.data.local.AppDatabase
import com.alx.moneytracker.data.repository.RoomTransactionRepository
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.ui.input.TransactionInputEvent
import com.alx.moneytracker.ui.input.TransactionInputViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Wires the *real* collaborators — a real in-memory Room [AppDatabase], the real
 * [RoomTransactionRepository], and the real [TransactionInputViewModel] — and drives the ViewModel
 * exactly as the UI would, through [TransactionInputViewModel.onEvent]. No fakes or mocks.
 *
 * Covered acceptance criteria:
 * - 9.1  — a valid submit persists the transaction and applies the source balance change atomically.
 * - 12.1 — the default wallet is loaded into state as the source through the observable stream.
 * - 12.2 — after the save, [RoomTransactionRepository.observeWallets] emits the updated balance.
 * - 12.3 — a successful save resets the running amount to 0 and clears the selected category.
 *
 * ### Why real dispatchers (not runTest / virtual time)
 * The ViewModel observes Room `Flow`s in `viewModelScope`. Room delivers those emissions on its own
 * `queryExecutor` background pool — NOT on any test dispatcher — so `runTest` + `advanceUntilIdle()`
 * cannot force or await them (that was the failure mode of an earlier virtual-time attempt). This
 * test therefore uses the ViewModel's real `Dispatchers.Main`/IO and *awaits* the real asynchronous
 * outcomes with a short polling helper ([awaitUntil]) under a timeout. The ViewModel is created on
 * the instrumentation main thread so `viewModelScope` (Main) has a real looper.
 *
 * The ViewModel lives in a [ViewModelStore] that is cleared in teardown BEFORE the database closes,
 * cancelling `viewModelScope` so the long-lived collectors stop before `db.close()`.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndSavePathTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RoomTransactionRepository
    private lateinit var viewModelStore: ViewModelStore

    private val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    private val fixedId = "e2e-id"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomTransactionRepository(
            transactionDao = db.transactionDao(),
            walletDao = db.walletDao(),
            categoryDao = db.categoryDao(),
            quickPresetDao = db.quickPresetDao()
            // real Dispatchers.IO for the atomic write
        )
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear() // cancels viewModelScope + its Room collectors
        db.close()
    }

    private fun createViewModel(): TransactionInputViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                TransactionInputViewModel(repository, fixedClock) { fixedId } as T
        }
        return ViewModelProvider(viewModelStore, factory)[TransactionInputViewModel::class.java]
    }

    /** Polls [condition] until true or the timeout elapses, failing with [message] on timeout. */
    private suspend fun awaitUntil(
        message: String,
        timeoutMs: Long = 5_000L,
        pollMs: Long = 25L,
        condition: () -> Boolean
    ) {
        withTimeout(timeoutMs) {
            while (!condition()) {
                kotlinx.coroutines.delay(pollMs)
            }
        }
        assertTrue(message, condition())
    }

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
    fun validExpenseSubmit_persists_updatesBalance_emitsFlow_andResets() = runBlocking {
        val walletId = insertWallet(name = "Cash", balance = 1_000L, isDefault = true)
        val categoryId = insertCategory(name = "Makan", type = TransactionType.EXPENSE)

        val viewModel = createViewModel()

        // 12.1 — the default wallet is auto-loaded as the source through the observable stream.
        // Room delivers the first emission on its own executor, so await it rather than assume sync.
        awaitUntil("default wallet should load as source (12.1)") {
            viewModel.uiState.value.sourceWalletId == walletId
        }

        // Compose a positive amount (300) via the numpad and select a category.
        viewModel.onEvent(TransactionInputEvent.DigitPressed(3))
        viewModel.onEvent(TransactionInputEvent.DigitPressed(0))
        viewModel.onEvent(TransactionInputEvent.DigitPressed(0))
        viewModel.onEvent(TransactionInputEvent.CategorySelected(categoryId))

        assertEquals(300L, viewModel.uiState.value.runningAmount)
        assertTrue("Submit should be enabled for a valid entry", viewModel.uiState.value.isSubmitEnabled)

        // Act: submit the entry through the real stack, then await the async save completing.
        viewModel.onEvent(TransactionInputEvent.Submit)
        awaitUntil("the transaction should be persisted (9.1)") { transactionCount() == 1L }

        // 9.1 — the persisted row has the expected fields and is unsynced.
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
        awaitUntil("state should reset after a successful save (12.3)") {
            val s = viewModel.uiState.value
            s.runningAmount == 0L && s.selectedCategoryId == null
        }
        val stateAfter = viewModel.uiState.value
        assertEquals(0L, stateAfter.runningAmount)
        assertNull(stateAfter.selectedCategoryId)
        assertEquals(TransactionType.EXPENSE, stateAfter.selectedType)
        assertEquals(walletId, stateAfter.sourceWalletId)
    }
}
