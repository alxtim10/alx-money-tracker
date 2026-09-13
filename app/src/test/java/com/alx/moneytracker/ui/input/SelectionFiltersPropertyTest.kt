package com.alx.moneytracker.ui.input

import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based tests for the pure selection/filter layer used by the transaction
 * input screen (Task 5.4). Covers three of the design's correctness properties:
 *
 *  - Property 5: presented categories match the selected type ([SelectionFilters.categoriesForType]).
 *  - Property 6: type and category selection are single-valued (state copy semantics).
 *  - Property 7: selectable wallet sets exclude archived wallets and, for the transfer
 *    destination, the source wallet ([SelectionFilters.selectableSourceWallets] /
 *    [SelectionFilters.selectableDestWallets]).
 *
 * Each property is implemented by exactly one property-based test using kotest-property,
 * running a minimum of 100 iterations. This file is kept distinct from the submit-gating
 * property test of Task 5.3.
 *
 * Validates: Requirements 5.2, 5.3, 3.1, 5.1, 4.2, 4.3
 */
class SelectionFiltersPropertyTest : FunSpec({

    val types: Arb<TransactionType> = Arb.of(TransactionType.entries)

    // A Category with a random type and archived flag; ids/names/icons vary but are
    // irrelevant to the filtering rule under test.
    val categories: Arb<Category> = Arb.bind(
        Arb.long(1L..1_000L),
        Arb.string(0..12),
        types,
        Arb.string(0..8),
        Arb.boolean()
    ) { id, name, type, icon, archived ->
        Category(id = id, name = name, type = type, icon = icon, isArchived = archived)
    }

    // A Wallet with a random archived flag; ids drawn from a small range so the
    // "exclude source" case is frequently exercised for destination filtering.
    val wallets: Arb<Wallet> = Arb.bind(
        Arb.long(1L..8L),
        Arb.string(0..12),
        Arb.long(0L..1_000_000L),
        Arb.boolean(),
        Arb.boolean()
    ) { id, name, balance, isDefault, archived ->
        Wallet(id = id, name = name, balance = balance, isDefault = isDefault, isArchived = archived)
    }

    // Feature: transaction-input-engine, Property 5
    test("Property 5: Presented categories match the selected type") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(categories, 0..30),
            types
        ) { list, chosenType ->
            val result = SelectionFilters.categoriesForType(list, chosenType)

            // Exactly the non-archived categories whose type matches the chosen type,
            // preserving input order.
            result shouldBe list.filter { !it.isArchived && it.type == chosenType }

            // And, restated as invariants: every presented category is non-archived and
            // matches the selected type.
            result.all { !it.isArchived && it.type == chosenType }.shouldBeTrue()
        }
    }

    // Feature: transaction-input-engine, Property 6
    test("Property 6: Type and category selection are single-valued") {
        // Generate a base state plus a candidate type and two distinct category values so
        // we can show that copying sets exactly the chosen value and nothing else.
        checkAll(
            PropTestConfig(iterations = 100),
            types,          // base state's initial type
            types,          // candidate type to select
            Arb.long(1L..1_000L), // base state's initial category
            Arb.long(1L..1_000L), // candidate category to select
            Arb.long(1L..1_000L)  // a different candidate for contrast
        ) { baseType, chosenType, baseCategory, chosenCategory, otherCategory ->
            val base = TransactionInputUiState(
                selectedType = baseType,
                selectedCategoryId = baseCategory
            )

            // Selecting a type: exactly that type is active afterward.
            val afterType = base.copy(selectedType = chosenType)
            afterType.selectedType shouldBe chosenType
            // No other enum value is simultaneously "active" — the field holds a single value.
            TransactionType.entries.count { it == afterType.selectedType } shouldBe 1

            // Selecting a category: exactly that category is active afterward.
            val afterCategory = base.copy(selectedCategoryId = chosenCategory)
            afterCategory.selectedCategoryId shouldBe chosenCategory
            // A different candidate is not the active category unless it equals the chosen one.
            if (otherCategory != chosenCategory) {
                afterCategory.selectedCategoryId shouldNotBe otherCategory
            }
        }
    }

    // Feature: transaction-input-engine, Property 7
    test("Property 7: Selectable wallet sets exclude archived and self") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(wallets, 0..20),
            Arb.long(1L..8L)
        ) { list, chosenSourceId ->
            // Source: exactly the non-archived wallets, order preserved.
            val sources = SelectionFilters.selectableSourceWallets(list)
            sources shouldBe list.filter { !it.isArchived }
            sources.none { it.isArchived }.shouldBeTrue()

            // Destination: non-archived wallets excluding the chosen source, order preserved.
            val dests = SelectionFilters.selectableDestWallets(list, chosenSourceId)
            dests shouldBe list.filter { !it.isArchived && it.id != chosenSourceId }
            dests.none { it.isArchived }.shouldBeTrue()
            // The destination set never contains the source wallet id.
            dests.none { it.id == chosenSourceId }.shouldBeTrue()
        }
    }
})
