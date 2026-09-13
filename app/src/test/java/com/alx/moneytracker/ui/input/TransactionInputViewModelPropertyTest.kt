package com.alx.moneytracker.ui.input

import com.alx.moneytracker.data.repository.TransactionRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.QuickPreset
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Property-based tests for [TransactionInputViewModel] transaction construction and post-save
 * reset (Task 6.3).
 *
 * Properties 10-13 each get exactly one property-based test (kotest-property, min 100 iterations).
 * The ViewModel runs its collectors and the save in `viewModelScope` (Dispatchers.Main), so the
 * suite installs an [UnconfinedTestDispatcher] as Main to make that work run synchronously under
 * `runTest`. A fixed [Clock] and a deterministic id generator are injected so identity/timestamp
 * assignment (Requirement 10) is deterministic and id uniqueness can be asserted across a
 * sequence of saves.
 *
 * Validates: Requirements 10.1, 10.3, 10.4, 10.5, 11.1, 12.3
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionInputViewModelPropertyTest : FunSpec({

    val fixedMillis = 123_456_789L
    val fixedClock: Clock = Clock.fixed(Instant.ofEpochMilli(fixedMillis), ZoneOffset.UTC)

    // Seeded ids for the default (source) wallet and the transfer destination wallet.
    val sourceWalletId = 1L
    val destWalletId = 2L

    beforeSpec {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    afterSpec {
        Dispatchers.resetMain()
    }

    /**
     * A minimal, deterministic [TransactionRepository] fake for these tests. It seeds a default
     * source wallet plus a second (transfer destination) wallet and a category for each type, and
     * records every [Transaction] handed to [saveTransaction]. It never touches a real database.
     *
     * File-private name so it doesn't collide with fakes defined by the task 6.4 tests in this
     * same package.
     */
    fun newRepository(): PropFakeRepository = PropFakeRepository(sourceWalletId, destWalletId)

    // Category ids seeded per type in the fake, so a valid CategorySelected can be dispatched.
    val categoryIdFor: (TransactionType) -> Long = { type ->
        when (type) {
            TransactionType.EXPENSE -> 10L
            TransactionType.INCOME -> 20L
            TransactionType.TRANSFER -> 30L
        }
    }

    /**
     * Drives [vm] into a fully valid, submittable state for the given input, then returns the
     * category id that was selected. The source wallet auto-loads from the seeded default wallet.
     */
    fun driveToValidState(
        vm: TransactionInputViewModel,
        type: TransactionType,
        amount: Long,
        note: String
    ): Long {
        vm.onEvent(TransactionInputEvent.TypeSelected(type))
        if (type == TransactionType.TRANSFER) {
            vm.onEvent(TransactionInputEvent.DestWalletSelected(destWalletId))
        }
        val categoryId = categoryIdFor(type)
        vm.onEvent(TransactionInputEvent.CategorySelected(categoryId))
        vm.onEvent(TransactionInputEvent.NoteChanged(note))
        // Set the running amount directly to the generated positive value via preset accumulation
        // from a zero start (equivalent to a single preset tap of `amount`).
        vm.onEvent(TransactionInputEvent.PresetTapped(amount))
        return categoryId
    }

    // Positive amounts within the valid, non-clamping input space.
    val amounts: Arb<Long> = Arb.long(1L..1_000_000_000L)
    val types: Arb<TransactionType> = Arb.enum<TransactionType>()
    val notes: Arb<String> = Arb.string(0..20)

    // Feature: transaction-input-engine, Property 10
    test("Property 10: Persisted transaction faithfully records the input state") {
        checkAll(PropTestConfig(iterations = 100), types, amounts, notes) { type, amount, note ->
            runTest {
                val repo = newRepository()
                val vm = TransactionInputViewModel(repo, fixedClock) { "id-fixed" }

                val categoryId = driveToValidState(vm, type, amount, note)
                vm.onEvent(TransactionInputEvent.Submit)

                val saved = repo.saved.single()
                saved.type shouldBe type
                saved.amount shouldBe amount
                saved.sourceWalletId shouldBe sourceWalletId
                saved.categoryId shouldBe categoryId
                saved.note shouldBe note
                saved.timestamp shouldBe fixedMillis
                saved.destWalletId shouldBe if (type == TransactionType.TRANSFER) destWalletId else null
            }
        }
    }

    // Feature: transaction-input-engine, Property 11
    test("Property 11: Every persisted transaction has a unique, non-empty id") {
        // A deterministic, monotonically increasing id generator so uniqueness is a real property
        // of the sequence rather than an accident of UUID randomness.
        val entrySequences: Arb<List<Pair<TransactionType, Long>>> =
            Arb.list(Arb.pair(types, amounts), 3..6)
        checkAll(PropTestConfig(iterations = 100), entrySequences) { entries ->
            runTest {
                val repo = newRepository()
                var counter = 0
                val vm = TransactionInputViewModel(repo, fixedClock) { "id-${counter++}" }

                for ((type, amount) in entries) {
                    driveToValidState(vm, type, amount, note = "")
                    vm.onEvent(TransactionInputEvent.Submit)
                }

                val ids = repo.saved.map { it.id }
                ids.size shouldBe entries.size
                ids.forEach { it.isNotEmpty() shouldBe true }
                ids.toSet().size shouldBe ids.size
            }
        }
    }

    // Feature: transaction-input-engine, Property 12
    test("Property 12: Every persisted transaction is marked unsynced") {
        checkAll(PropTestConfig(iterations = 100), types, amounts, notes) { type, amount, note ->
            runTest {
                val repo = newRepository()
                val vm = TransactionInputViewModel(repo, fixedClock) { "id-fixed" }

                driveToValidState(vm, type, amount, note)
                vm.onEvent(TransactionInputEvent.Submit)

                repo.saved.single().isSynced shouldBe false
            }
        }
    }

    // Feature: transaction-input-engine, Property 13
    test("Property 13: A successful save resets amount and category") {
        checkAll(PropTestConfig(iterations = 100), types, amounts, notes) { type, amount, note ->
            runTest {
                val repo = newRepository()
                val vm = TransactionInputViewModel(repo, fixedClock) { "id-fixed" }

                driveToValidState(vm, type, amount, note)
                vm.onEvent(TransactionInputEvent.Submit)

                val state = vm.uiState.value
                // Amount and category are reset for the next entry (Requirement 12.3).
                state.runningAmount shouldBe 0L
                state.selectedCategoryId shouldBe null
                // Type and source wallet selections are preserved.
                state.selectedType shouldBe type
                state.sourceWalletId shouldBe sourceWalletId
            }
        }
    }
})

/**
 * File-private fake repository for the property tests. Seeds a default source wallet and a second
 * transfer-destination wallet plus one category per type, and records every saved [Transaction].
 */
private class PropFakeRepository(
    sourceWalletId: Long,
    destWalletId: Long
) : TransactionRepository {

    val saved = mutableListOf<Transaction>()

    /** Toggle to make [saveTransaction] return a failure instead of success. */
    var failWith: Throwable? = null

    private val sourceWallet =
        Wallet(id = sourceWalletId, name = "Utama", balance = 1_000_000L, isDefault = true, isArchived = false)
    private val destWallet =
        Wallet(id = destWalletId, name = "Tabungan", balance = 0L, isDefault = false, isArchived = false)

    private val wallets = MutableStateFlow(listOf(sourceWallet, destWallet))
    private val defaultWallet = MutableStateFlow<Wallet?>(sourceWallet)

    private val categories = MutableStateFlow(
        listOf(
            Category(id = 10L, name = "Makan", type = TransactionType.EXPENSE, icon = "food", isArchived = false),
            Category(id = 20L, name = "Gaji", type = TransactionType.INCOME, icon = "salary", isArchived = false),
            Category(id = 30L, name = "Pindah", type = TransactionType.TRANSFER, icon = "swap", isArchived = false)
        )
    )

    override fun observeWallets(): Flow<List<Wallet>> = wallets

    override fun observeCategories(type: TransactionType): Flow<List<Category>> =
        categories.map { list -> list.filter { it.type == type } }

    override fun observeQuickPresets(): Flow<List<QuickPreset>> =
        MutableStateFlow(emptyList())

    override fun observeDefaultWallet(): Flow<Wallet?> = defaultWallet

    override suspend fun saveTransaction(transaction: Transaction): Result<Unit> {
        failWith?.let { return Result.failure(it) }
        saved += transaction
        return Result.success(Unit)
    }
}
