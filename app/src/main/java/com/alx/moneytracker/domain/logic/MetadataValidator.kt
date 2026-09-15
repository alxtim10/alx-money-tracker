package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.CategoryFields
import com.alx.moneytracker.domain.NewWallet
import com.alx.moneytracker.domain.PresetFields
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.ValidationResult

/**
 * Pure validation logic for Customization & Metadata (Scope 3) submissions.
 *
 * Each function trims leading/trailing whitespace from names and labels before applying its length
 * check, and applies inclusive range checks to balances and amounts. On success it returns a
 * [ValidationResult.Valid] carrying the sanitized write-side value ready to persist; on failure it
 * returns a [ValidationResult.Invalid] identifying the specific offending field via
 * [ValidationError].
 *
 * The object holds no Android dependencies so it can be exercised directly on the JVM by unit and
 * property tests. It never touches persistence — callers reject the submission when validation
 * fails, leaving the target store unchanged.
 */
object MetadataValidator {

    /** Maximum permitted monetary value (smallest currency unit) for balances and amounts. */
    const val MAX_MONEY: Long = 999_999_999_999L

    /** Maximum wallet name length, in characters, after trimming. */
    const val WALLET_NAME_MAX: Int = 100

    /** Maximum category name length, in characters, after trimming. */
    const val CATEGORY_NAME_MAX: Int = 50

    /** Maximum quick preset label length, in characters, after trimming. */
    const val PRESET_LABEL_MAX: Int = 50

    /**
     * Validates a new wallet submission.
     *
     * Trims [rawName]; the submission is valid when the trimmed name is 1..[WALLET_NAME_MAX]
     * characters and [balance] is within `0..MAX_MONEY` inclusive. The name is checked first: an
     * empty trimmed name yields [ValidationError.NAME_EMPTY], one exceeding the bound yields
     * [ValidationError.NAME_TOO_LONG], and a balance outside the range yields
     * [ValidationError.BALANCE_OUT_OF_RANGE] (Requirements 1.1, 1.2, 1.3).
     *
     * @return [ValidationResult.Valid] carrying a [NewWallet] with the trimmed name and submitted
     *   balance, or [ValidationResult.Invalid] identifying the offending field.
     */
    fun validateWalletCreate(rawName: String, balance: Long): ValidationResult<NewWallet> {
        val nameError = validateName(rawName, WALLET_NAME_MAX)
        if (nameError != null) return ValidationResult.Invalid(nameError)
        if (balance !in 0L..MAX_MONEY) {
            return ValidationResult.Invalid(ValidationError.BALANCE_OUT_OF_RANGE)
        }
        return ValidationResult.Valid(NewWallet(name = rawName.trim(), balance = balance))
    }

    /**
     * Validates an updated wallet name.
     *
     * Trims [rawName]; valid when the trimmed name is 1..[WALLET_NAME_MAX] characters, otherwise
     * returns [ValidationError.NAME_EMPTY] or [ValidationError.NAME_TOO_LONG] (Requirements 3.1,
     * 3.2).
     *
     * @return [ValidationResult.Valid] carrying the trimmed name, or [ValidationResult.Invalid].
     */
    fun validateWalletName(rawName: String): ValidationResult<String> {
        val nameError = validateName(rawName, WALLET_NAME_MAX)
        if (nameError != null) return ValidationResult.Invalid(nameError)
        return ValidationResult.Valid(rawName.trim())
    }

    /**
     * Validates a wallet balance (used for a Direct_Balance_Override target).
     *
     * Valid when [balance] is within `0..MAX_MONEY` inclusive; otherwise returns
     * [ValidationError.BALANCE_OUT_OF_RANGE] (Requirements 4.1, 4.2).
     *
     * @return [ValidationResult.Valid] carrying the balance, or [ValidationResult.Invalid].
     */
    fun validateBalance(balance: Long): ValidationResult<Long> {
        if (balance !in 0L..MAX_MONEY) {
            return ValidationResult.Invalid(ValidationError.BALANCE_OUT_OF_RANGE)
        }
        return ValidationResult.Valid(balance)
    }

    /**
     * Validates a category submission.
     *
     * Trims [rawName]; valid when the trimmed name is 1..[CATEGORY_NAME_MAX] characters AND [type]
     * is INCOME or EXPENSE. A TRANSFER type yields [ValidationError.TYPE_NOT_PERMITTED]; the name
     * is checked first (Requirements 7.1, 7.2, 7.3, 9.2, 9.3).
     *
     * @param icon Optional icon identifier carried through unchanged; null when none was submitted.
     * @return [ValidationResult.Valid] carrying [CategoryFields] with the trimmed name, submitted
     *   type, and icon, or [ValidationResult.Invalid] identifying the offending field.
     */
    fun validateCategory(
        rawName: String,
        type: TransactionType,
        icon: String? = null
    ): ValidationResult<CategoryFields> {
        val nameError = validateName(rawName, CATEGORY_NAME_MAX)
        if (nameError != null) return ValidationResult.Invalid(nameError)
        if (type == TransactionType.TRANSFER) {
            return ValidationResult.Invalid(ValidationError.TYPE_NOT_PERMITTED)
        }
        return ValidationResult.Valid(
            CategoryFields(name = rawName.trim(), type = type, icon = icon)
        )
    }

    /**
     * Validates a quick preset submission.
     *
     * Trims [rawLabel]; valid when [amount] is within `1..MAX_MONEY` inclusive AND the trimmed
     * label is 1..[PRESET_LABEL_MAX] characters. An out-of-range amount yields
     * [ValidationError.AMOUNT_OUT_OF_RANGE] (checked first); an invalid label yields
     * [ValidationError.LABEL_EMPTY] or [ValidationError.LABEL_TOO_LONG] (Requirements 11.1, 11.2,
     * 11.3, 13.2, 13.3).
     *
     * @return [ValidationResult.Valid] carrying [PresetFields] with the submitted amount and trimmed
     *   label, or [ValidationResult.Invalid] identifying the offending field.
     */
    fun validatePreset(amount: Long, rawLabel: String): ValidationResult<PresetFields> {
        if (amount !in 1L..MAX_MONEY) {
            return ValidationResult.Invalid(ValidationError.AMOUNT_OUT_OF_RANGE)
        }
        val trimmed = rawLabel.trim()
        if (trimmed.isEmpty()) return ValidationResult.Invalid(ValidationError.LABEL_EMPTY)
        if (trimmed.length > PRESET_LABEL_MAX) {
            return ValidationResult.Invalid(ValidationError.LABEL_TOO_LONG)
        }
        return ValidationResult.Valid(PresetFields(amount = amount, label = trimmed))
    }

    /**
     * Applies the shared trim-then-length check to a name/label.
     *
     * @return [ValidationError.NAME_EMPTY] when the trimmed value is empty,
     *   [ValidationError.NAME_TOO_LONG] when it exceeds [max], or `null` when valid.
     */
    private fun validateName(raw: String, max: Int): ValidationError? {
        val trimmed = raw.trim()
        return when {
            trimmed.isEmpty() -> ValidationError.NAME_EMPTY
            trimmed.length > max -> ValidationError.NAME_TOO_LONG
            else -> null
        }
    }
}
