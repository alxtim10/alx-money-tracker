package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.ValidationResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 * Property-based test for [MetadataValidator] (Task 2.2).
 *
 * Exactly one property test implementing Property 8, using kotest-property with a minimum of 100
 * iterations. It exercises every validator surface — wallet create, wallet name, balance, category,
 * and preset — across the full input space: names/labels that are valid (1..bound characters,
 * including surrounding whitespace to exercise trimming), empty, all-whitespace, and over-bound;
 * balances/amounts in-range and out-of-range (negative and `> MAX_MONEY`); and every
 * [TransactionType] including TRANSFER.
 *
 * For each generated input the test computes, from independent reference logic, both whether the
 * submission should be accepted (the iff acceptance characterization) and, when rejected, the
 * specific offending field. It then asserts the validator agrees: accepted inputs yield a
 * [ValidationResult.Valid] carrying the trimmed/sanitized value, and rejected inputs yield a
 * [ValidationResult.Invalid] naming exactly that field. Since [MetadataValidator] is pure and
 * touches no persistence, "leaves the target store unchanged" holds by construction — the validator
 * never reaches a store, and callers only persist on [ValidationResult.Valid].
 *
 * Validates: Requirements 1.1, 1.2, 1.3, 3.1, 3.2, 4.1, 4.2, 7.1, 7.2, 7.3, 9.2, 9.3, 11.1, 11.2,
 * 11.3, 13.2, 13.3
 */
class MetadataValidatorPropertyTest : FunSpec({

    val maxMoney = MetadataValidator.MAX_MONEY

    // Non-whitespace letters so a generated body's trimmed length equals its own length.
    val letters = ('a'..'z').toList()

    /**
     * A name/label generator spanning the full validity space for a given [max] bound:
     * - valid trimmed content of 1..max characters, optionally wrapped in surrounding whitespace
     *   (so trimming is exercised and the trimmed length is what determines validity);
     * - empty and all-whitespace strings (which trim to empty → NAME/LABEL empty);
     * - over-bound trimmed content (max+1 .. max+20 characters → too long).
     */
    fun rawNames(max: Int): Arb<String> {
        val whitespace: Arb<String> = Arb.of("", " ", "  ", "\t", "\n", "  \t ")
        return arbitrary {
            when (Arb.int(0..3).bind()) {
                0 -> {
                    // Valid trimmed length 1..max, wrapped in optional surrounding whitespace.
                    val len = Arb.int(1..max).bind()
                    val body = (0 until len).map { letters[Arb.int(0..25).bind()] }.joinToString("")
                    whitespace.bind() + body + whitespace.bind()
                }
                1 -> "" // empty
                2 -> whitespace.bind().ifEmpty { " " } // all-whitespace → trims to empty
                else -> {
                    // Over-bound trimmed content: max+1 .. max+20 non-whitespace characters.
                    val len = Arb.int((max + 1)..(max + 20)).bind()
                    (0 until len).map { letters[Arb.int(0..25).bind()] }.joinToString("")
                }
            }
        }
    }

    // Monetary generator spanning below-range (negative), in-range, and above-range values.
    val moneyValues: Arb<Long> = Arb.long(-1_000L..(maxMoney + 1_000L))

    // Feature: customization-metadata, Property 8: Validation accepts valid input and rejects invalid input without mutating any store
    test("Property 8: Validation accepts valid input and rejects invalid input without mutating any store") {
        checkAll(
            PropTestConfig(iterations = 100),
            rawNames(MetadataValidator.WALLET_NAME_MAX),
            rawNames(MetadataValidator.CATEGORY_NAME_MAX),
            rawNames(MetadataValidator.PRESET_LABEL_MAX),
            moneyValues,
            moneyValues,
            Arb.enum<TransactionType>()
        ) { walletName, categoryName, presetLabel, balance, amount, type ->

            // ---- validateWalletCreate: name (1..100) checked first, then balance (0..MAX_MONEY) ----
            run {
                val trimmed = walletName.trim()
                val expected = nameError(trimmed, MetadataValidator.WALLET_NAME_MAX)
                    ?: if (balance !in 0L..maxMoney) ValidationError.BALANCE_OUT_OF_RANGE else null
                when (val r = MetadataValidator.validateWalletCreate(walletName, balance)) {
                    is ValidationResult.Valid -> {
                        expected shouldBe null
                        r.value shouldBe NewWallet(name = trimmed, balance = balance)
                    }
                    is ValidationResult.Invalid -> r.reason shouldBe expected
                }
            }

            // ---- validateWalletName: name (1..100) ----
            run {
                val trimmed = walletName.trim()
                val expected = nameError(trimmed, MetadataValidator.WALLET_NAME_MAX)
                when (val r = MetadataValidator.validateWalletName(walletName)) {
                    is ValidationResult.Valid -> {
                        expected shouldBe null
                        r.value shouldBe trimmed
                    }
                    is ValidationResult.Invalid -> r.reason shouldBe expected
                }
            }

            // ---- validateBalance: (0..MAX_MONEY) ----
            run {
                val expected =
                    if (balance !in 0L..maxMoney) ValidationError.BALANCE_OUT_OF_RANGE else null
                when (val r = MetadataValidator.validateBalance(balance)) {
                    is ValidationResult.Valid -> {
                        expected shouldBe null
                        r.value shouldBe balance
                    }
                    is ValidationResult.Invalid -> r.reason shouldBe expected
                }
            }

            // ---- validateCategory: name (1..50) checked first, then type != TRANSFER ----
            run {
                val trimmed = categoryName.trim()
                val expected = nameError(trimmed, MetadataValidator.CATEGORY_NAME_MAX)
                    ?: if (type == TransactionType.TRANSFER) ValidationError.TYPE_NOT_PERMITTED else null
                when (val r = MetadataValidator.validateCategory(categoryName, type)) {
                    is ValidationResult.Valid -> {
                        expected shouldBe null
                        r.value shouldBe CategoryFields(name = trimmed, type = type, icon = null)
                    }
                    is ValidationResult.Invalid -> r.reason shouldBe expected
                }
            }

            // ---- validatePreset: amount (1..MAX_MONEY) checked first, then label (1..50) ----
            run {
                val trimmed = presetLabel.trim()
                val expected = when {
                    amount !in 1L..maxMoney -> ValidationError.AMOUNT_OUT_OF_RANGE
                    trimmed.isEmpty() -> ValidationError.LABEL_EMPTY
                    trimmed.length > MetadataValidator.PRESET_LABEL_MAX -> ValidationError.LABEL_TOO_LONG
                    else -> null
                }
                when (val r = MetadataValidator.validatePreset(amount, presetLabel)) {
                    is ValidationResult.Valid -> {
                        expected shouldBe null
                        r.value shouldBe PresetFields(amount = amount, label = trimmed)
                    }
                    is ValidationResult.Invalid -> r.reason shouldBe expected
                }
            }
        }
    }
})

/**
 * Independent reference for the trim-then-length name/label check: [ValidationError.NAME_EMPTY] when
 * the already-trimmed [trimmed] is empty, [ValidationError.NAME_TOO_LONG] when it exceeds [max], or
 * `null` when the length is within `1..max`.
 */
private fun nameError(trimmed: String, max: Int): ValidationError? = when {
    trimmed.isEmpty() -> ValidationError.NAME_EMPTY
    trimmed.length > max -> ValidationError.NAME_TOO_LONG
    else -> null
}
