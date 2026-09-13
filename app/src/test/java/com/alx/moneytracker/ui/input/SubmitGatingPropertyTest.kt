package com.alx.moneytracker.ui.input

import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll

/**
 * Property-based test for submit gating (Task 5.3).
 *
 * Exactly one property test covering Property 4, using kotest-property with a minimum of
 * 100 iterations. Generators build full [TransactionInputUiState] values that vary the
 * running amount (including 0 and positive), the selected category (sometimes null), the
 * transaction type (across all entries), and the source/destination wallets (nullable, and
 * sometimes equal, sometimes distinct, sometimes null) so the transfer branch is exercised.
 *
 * Validates: Requirements 7.1, 7.2, 7.3, 4.4
 */
class SubmitGatingPropertyTest : FunSpec({

    // Amounts across the full valid space, including the boundary 0 and positive values.
    val amounts: Arb<Long> = Arb.long(0L..1_000_000L)

    // Category id that is sometimes null (no category selected).
    val categoryIds: Arb<Long?> = Arb.long(0L..100L).orNull()

    // Wallet ids drawn from a small pool so source and dest sometimes coincide and sometimes
    // differ; nullable so "no wallet selected" is also covered.
    val walletIds: Arb<Long?> = Arb.long(1L..5L).orNull()

    val types: Arb<TransactionType> = Arb.of(TransactionType.entries)

    // Feature: transaction-input-engine, Property 4
    test("Property 4: Submit control is enabled exactly when the entry is valid") {
        checkAll(
            PropTestConfig(iterations = 100),
            amounts,
            categoryIds,
            types,
            walletIds,
            walletIds
        ) { amount, categoryId, type, sourceId, destId ->
            val state = TransactionInputUiState(
                runningAmount = amount,
                selectedType = type,
                sourceWalletId = sourceId,
                destWalletId = destId,
                selectedCategoryId = categoryId
            )

            // Independently-computed oracle for the gating rule.
            val expected = amount > 0L &&
                categoryId != null &&
                (type != TransactionType.TRANSFER || (destId != null && destId != sourceId))

            SubmitValidator.isSubmitEnabled(state) shouldBe expected
            state.isSubmitEnabled shouldBe expected
        }
    }
})
