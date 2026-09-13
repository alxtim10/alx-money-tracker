package com.alx.moneytracker.domain.logic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

/**
 * Property-based tests for [AmountReducer] (Task 1.3).
 *
 * Each of the three properties below is implemented by exactly one property-based
 * test using kotest-property, running a minimum of 100 iterations. Generators cover
 * amounts in `0..MAX_AMOUNT`, digits in `0..9`, and lists of preset values. Every
 * assertion accounts for the reducer clamping its result at [AmountReducer.MAX_AMOUNT].
 *
 * Validates: Requirements 1.2, 1.3, 1.4, 2.2, 2.3
 */
class AmountReducerPropertyTest : FunSpec({

    val max = AmountReducer.MAX_AMOUNT

    // Amounts across the full valid input space, including the boundaries 0 and MAX_AMOUNT.
    val amounts: Arb<Long> = Arb.long(0L..max)

    // Feature: transaction-input-engine, Property 1
    test("Property 1: Digit append multiplies and adds") {
        checkAll(PropTestConfig(iterations = 100), amounts, Arb.int(0..9)) { current, digit ->
            // Expected value computed overflow-safely so the oracle itself never wraps.
            val expected =
                if (current > max / 10) max
                else {
                    val appended = current * 10 + digit
                    if (appended > max) max else appended
                }
            AmountReducer.appendDigit(current, digit) shouldBe expected
        }
    }

    // Feature: transaction-input-engine, Property 2
    test("Property 2: Digit delete drops the least significant digit") {
        checkAll(PropTestConfig(iterations = 100), amounts) { current ->
            // Integer division by 10; in particular deleting from 0 yields 0.
            AmountReducer.deleteDigit(current) shouldBe current / 10
        }
    }

    // Feature: transaction-input-engine, Property 3
    test("Property 3: Preset taps accumulate additively") {
        val presetValues: Arb<Long> = Arb.long(0L..max)
        checkAll(
            PropTestConfig(iterations = 100),
            amounts,
            Arb.list(presetValues, 0..10)
        ) { start, presets ->
            // Apply every tap in order through the reducer.
            val actual = presets.fold(start) { acc, value -> AmountReducer.addPreset(acc, value) }

            // Oracle: additive accumulation clamped at MAX_AMOUNT, computed overflow-safely.
            val expected = presets.fold(start) { acc, value ->
                if (value > max - acc) max else acc + value
            }

            actual shouldBe expected
        }
    }
})
