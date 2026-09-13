package com.alx.moneytracker.ui.input

import com.alx.moneytracker.data.repository.TransactionRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Unit tests for ViewModel state defaults (Task 6.4).
 *
 * These are example/edge-case unit tests (not property tests) that pin down the initial
 * state contract of [TransactionInputViewModel]:
 *  - the default transaction type is EXPENSE (Requirement 3.2),
 *  - the source wallet is set to the default wallet on load (Requirement 4.1),
 *  - an untouched note persists as "" (Requirement 6.4).
 *
 * The ViewModel collects the repository Flows in its `init` block via `viewModelScope`.
 * Setting the Main dispatcher to an [UnconfinedTestDispatcher] makes those collectors run
 * eagerly and synchronously during construction, so the loaded state is observable
 * immediately without an emulator or Room.
 *
 * Validates: Requirements 3.2, 4.1, 6.4
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionInputViewModelDefaultsTest : FunSpec({

    // Fixed clock and deterministic id so a built transaction is reproducible.
    val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)
    val fixedId = "id-1"

    // Default wallet id emitted by the fake repository's observeDefaultWallet().
    val defaultWalletId = 42L

    fun newViewModel(
        repository: DefaultsFakeRepository
    ): TransactionInputViewModel =
        TransactionInputViewModel(
            repository = repository,
            clock = fixedClock,
            idGenerator = { fixedId }
        )

    beforeTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    afterTest {
        Dispatchers.resetMain()
    }

    test("default transaction type is EXPENSE (Req 3.2)") {
        runTest {
            val repository = DefaultsFakeRepository(defaultWalletId = defaultWalletId)
            val viewModel = newViewModel(repository)

            viewModel.uiState.value.selectedType shouldBe TransactionType.EXPENSE
        }
    }

    test("source wallet is set to the default wallet on load (Req 4.1)") {
        runTest {
            val repository = DefaultsFakeRepository(defaultWalletId = defaultWalletId)
            val viewModel = newViewModel(repository)

            viewModel.uiState.value.sourceWalletId shouldBe defaultWalletId
        }
    }

    test("a user-selected source wallet is not overwritten by the default (Req 4.1)") {
        runTest {
            val repository = DefaultsFakeRepository(defaultWalletId = defaultWalletId)
            val viewModel = newViewModel(repository)

            // Choose a source before/independent of the default; the default (42) must not clobber it.
            viewModel.onEvent(TransactionInputEvent.SourceWalletSelected(7L))

            viewModel.uiState.value.sourceWalletId shouldBe 7L
        }
    }

    test("empty note persists as \"\" on submit (Req 6.4)") {
        runTest {
            // Seed a category so the entry can become submittable; default wallet auto-sets source.
            val category = Category(
                id = 100L,
                name = "Makan",
                type = TransactionType.EXPENSE,
                icon = "food",
                isArchived = false
            )
            val repository = DefaultsFakeRepository(
                defaultWalletId = defaultWalletId,
                categories = listOf(category)
            )
            val viewModel = newViewModel(repository)

            // Drive to a valid EXPENSE entry WITHOUT ever setting a note (note stays default "").
            viewModel.onEvent(TransactionInputEvent.CategorySelected(category.id))
            viewModel.onEvent(TransactionInputEvent.DigitPressed(5)) // amount > 0

            // Sanity: the entry must be submittable so Submit actually persists.
            viewModel.uiState.value.isSubmitEnabled shouldBe true

            viewModel.onEvent(TransactionInputEvent.Submit)

            val saved = repository.savedTransactions.single()
            saved.note shouldBe ""
        }
    }
})

/**
 * File-private fake [TransactionRepository] for the defaults tests. Distinct from any other
 * spec fake to avoid same-package redeclaration collisions.
 *
 * Flows are single-emission [flowOf] streams: observeDefaultWallet emits a wallet with
 * [defaultWalletId]; the list observers emit the seeded lists (empty by default);
 * saveTransaction records the transaction and reports success.
 */
private class DefaultsFakeRepository(
    private val defaultWalletId: Long,
    private val wallets: List<Wallet> = emptyList(),
    private val categories: List<Category> = emptyList(),
    private val presets: List<QuickPreset> = emptyList()
) : TransactionRepository {

    val savedTransactions = mutableListOf<Transaction>()

    private val defaultWallet = Wallet(
        id = defaultWalletId,
        name = "Cash",
        balance = 0L,
        isDefault = true,
        isArchived = false
    )

    override fun observeWallets(): Flow<List<Wallet>> = flowOf(wallets)

    override fun observeCategories(type: TransactionType): Flow<List<Category>> =
        flowOf(categories.filter { it.type == type })

    override fun observeQuickPresets(): Flow<List<QuickPreset>> = flowOf(presets)

    override fun observeDefaultWallet(): Flow<Wallet?> = flowOf(defaultWallet)

    override suspend fun saveTransaction(transaction: Transaction): Result<Unit> {
        savedTransactions.add(transaction)
        return Result.success(Unit)
    }
}
