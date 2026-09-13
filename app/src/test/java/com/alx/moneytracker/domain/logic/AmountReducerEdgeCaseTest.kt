package com.alx.moneytracker.domain.logic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Example / edge-case unit tests for [AmountReducer].
 *
 * These complement the property-based tests (task 1.3) by pinning down the exact
 * boundary behaviours called out in the design's error-handling table:
 *  - deleting a digit from zero stays at zero (Requirement 1.4),
 *  - appending a digit to a maxed-out amount stays clamped at MAX_AMOUNT (Requirement 2.2),
 *  - adding a preset that would overflow the ceiling stays clamped at MAX_AMOUNT (Requirement 2.2).
 *
 * A distinct file name from the property tests avoids a class/file collision.
 */
class AmountReducerEdgeCaseTest : StringSpec({

    // --- deleteDigit edge cases (Requirement 1.4) ---

    "deleteDigit(0) stays at 0" {
        AmountReducer.deleteDigit(0L) shouldBe 0L
    }

    "deleteDigit on a single-digit amount collapses to 0" {
        AmountReducer.deleteDigit(7L) shouldBe 0L
    }

    "deleteDigit drops the least significant digit via integer division" {
        AmountReducer.deleteDigit(12_345L) shouldBe 1_234L
    }

    "repeated deleteDigit eventually reaches 0 and stays there" {
        var amount = 42L
        amount = AmountReducer.deleteDigit(amount) // 4
        amount = AmountReducer.deleteDigit(amount) // 0
        amount = AmountReducer.deleteDigit(amount) // still 0
        amount shouldBe 0L
    }

    // --- appendDigit clamping (Requirements 1.2, 2.2) ---

    "appendDigit to a value already at MAX_AMOUNT stays clamped" {
        AmountReducer.appendDigit(AmountReducer.MAX_AMOUNT, 5) shouldBe AmountReducer.MAX_AMOUNT
    }

    "appendDigit that would exceed MAX_AMOUNT clamps to MAX_AMOUNT" {
        // MAX_AMOUNT / 10 is the largest value that can still be multiplied by 10
        // without crossing the ceiling; one more than that must clamp.
        val justOverTheMultiplyThreshold = AmountReducer.MAX_AMOUNT / 10 + 1
        AmountReducer.appendDigit(justOverTheMultiplyThreshold, 0) shouldBe AmountReducer.MAX_AMOUNT
    }

    "appendDigit below the ceiling behaves as current * 10 + digit" {
        AmountReducer.appendDigit(123L, 4) shouldBe 1_234L
    }

    "appendDigit to 0 yields the digit" {
        AmountReducer.appendDigit(0L, 9) shouldBe 9L
    }

    // --- addPreset clamping (Requirements 2.2, 2.3) ---

    "addPreset at MAX_AMOUNT stays clamped" {
        AmountReducer.addPreset(AmountReducer.MAX_AMOUNT, 10_000L) shouldBe AmountReducer.MAX_AMOUNT
    }

    "addPreset with a huge value that would overflow clamps to MAX_AMOUNT" {
        AmountReducer.addPreset(1L, Long.MAX_VALUE) shouldBe AmountReducer.MAX_AMOUNT
    }

    "addPreset that would just cross the ceiling clamps to MAX_AMOUNT" {
        AmountReducer.addPreset(AmountReducer.MAX_AMOUNT - 1L, 2L) shouldBe AmountReducer.MAX_AMOUNT
    }

    "addPreset that lands exactly on MAX_AMOUNT is preserved (no over-clamp)" {
        AmountReducer.addPreset(AmountReducer.MAX_AMOUNT - 1L, 1L) shouldBe AmountReducer.MAX_AMOUNT
    }

    "addPreset below the ceiling is a plain sum" {
        AmountReducer.addPreset(10_000L, 50_000L) shouldBe 60_000L
    }
})
