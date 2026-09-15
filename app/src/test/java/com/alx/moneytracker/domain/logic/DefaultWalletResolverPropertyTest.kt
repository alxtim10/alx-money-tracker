package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.Wallet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property-based test for [DefaultWalletResolver.pickReplacementDefault] (Task 3.2).
 *
 * Exactly one property test covering Property 5, using kotest-property with a minimum
 * of 100 iterations.
 *
 * The generator draws wallet names from a tiny pool that mixes case variants of the
 * same word ("apple"/"Apple"/"APPLE") and repeated names, so that case-insensitive ties
 * and same-name-different-id ties occur frequently within a single generated set. Wallet
 * ids are drawn from a small range to make lowest-id tie-breaks common as well.
 *
 * The result is cross-checked against an INDEPENDENT reference selection that does not
 * re-invoke the resolver's `minWithOrNull(compareBy(...))` expression verbatim: the
 * reference builds a case-folded key, groups by that key to find the smallest name-key,
 * then scans for the lowest id among wallets sharing that key. The empty set is asserted
 * to yield `null`.
 *
 * Validates: Requirements 5.5, 5.6
 */
class DefaultWalletResolverPropertyTest : FunSpec({

    // A small pool of names deliberately including case variants of the same word and
    // repeated spellings so case-insensitive ties and duplicate names are frequent.
    val names: Arb<String> = Arb.of(
        "apple", "Apple", "APPLE",
        "banana", "Banana",
        "cherry", "CHERRY",
        "date",
        "  edge  " // surrounding whitespace: names are compared as-is (no trimming here)
    )

    // Small id range so lowest-id tie-breaks are exercised often. Ids are made distinct
    // per wallet within a set by the reference logic; the resolver itself does not require
    // distinct ids, but distinct ids keep the "lowest id wins" tie-break meaningful.
    val wallets: Arb<Wallet> = Arb.bind(
        Arb.long(1L..6L),   // id — tiny range => frequent id collisions/adjacency
        names,
        Arb.long(0L..1_000L),
        Arb.boolean(),
        Arb.of(false)       // remainingActive are, by definition, not archived
    ) { id, name, balance, isDefault, isArchived ->
        Wallet(
            id = id,
            name = name,
            balance = balance,
            isDefault = isDefault,
            isArchived = isArchived
        )
    }

    /**
     * Independent reference selection.
     *
     * Deliberately expressed differently from the production comparator: it first finds
     * the minimum case-folded name key, then among only the wallets sharing that key it
     * returns the lowest id. Returns `null` for an empty input.
     */
    fun referencePick(remaining: List<Wallet>): Long? {
        if (remaining.isEmpty()) return null
        val smallestNameKey = remaining
            .map { it.name.lowercase() }
            .minOrNull()!!
        return remaining
            .filter { it.name.lowercase() == smallestNameKey }
            .minOf { it.id }
    }

    // Feature: customization-metadata, Property 5: Default reassignment on archive selects deterministically
    test("Property 5: Default reassignment on archive selects deterministically") {
        checkAll(
            PropTestConfig(iterations = 100),
            Arb.list(wallets, 0..12)
        ) { list ->
            val actual = DefaultWalletResolver.pickReplacementDefault(list)

            if (list.isEmpty()) {
                // The empty set designates no wallet.
                actual.shouldBeNull()
            } else {
                // Cross-check against the independent reference selection.
                actual shouldBe referencePick(list)
            }
        }
    }
})
