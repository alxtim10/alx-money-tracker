package com.alx.moneytracker.domain

/**
 * Result of validating a metadata submission (a wallet, category, or quick preset field set).
 *
 * On success the sanitized/normalized value is carried in [Valid]; on failure the specific reason
 * is carried in [Invalid] so the caller can surface an indication identifying the invalid field.
 *
 * Kept in the Android-independent `domain` package so it is testable on the JVM.
 *
 * @param T The type of the sanitized value produced on successful validation.
 */
sealed interface ValidationResult<out T> {

    /**
     * Validation succeeded; [value] is the sanitized/normalized result ready to persist.
     */
    data class Valid<T>(val value: T) : ValidationResult<T>

    /**
     * Validation failed; [reason] identifies the specific invalid field.
     */
    data class Invalid(val reason: ValidationError) : ValidationResult<Nothing>
}

/**
 * Identifies the specific reason a metadata submission failed validation.
 */
enum class ValidationError {
    /** A required name/label was empty after trimming. */
    NAME_EMPTY,

    /** A name exceeded its maximum allowed length after trimming. */
    NAME_TOO_LONG,

    /** A wallet balance was negative or exceeded the maximum permitted value. */
    BALANCE_OUT_OF_RANGE,

    /** A preset amount was outside its permitted range. */
    AMOUNT_OUT_OF_RANGE,

    /** A preset label was empty after trimming. */
    LABEL_EMPTY,

    /** A preset label exceeded its maximum allowed length after trimming. */
    LABEL_TOO_LONG,

    /** A category [TransactionType] was TRANSFER, which is not permitted for a category. */
    TYPE_NOT_PERMITTED
}
