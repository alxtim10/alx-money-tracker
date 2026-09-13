package com.alx.moneytracker.domain.logic

/**
 * Pure amount-accumulation logic for the transaction input numpad.
 *
 * All amounts are non-negative [Long] values expressed in the smallest currency
 * unit. This object holds no Android dependencies so it can be exercised directly
 * on the JVM by unit and property tests.
 *
 * Every operation clamps its result to the inclusive range `0..`[MAX_AMOUNT].
 * Clamping is performed with overflow-safe arithmetic so a large [appendDigit] or
 * [addPreset] can never wrap around to a negative value.
 */
object AmountReducer {

    /** Upper bound for a running amount; guards against arithmetic overflow. */
    const val MAX_AMOUNT: Long = 999_999_999_999L

    /**
     * Appends [digit] (0..9) to [current], i.e. `current * 10 + digit`.
     *
     * The result is clamped at [MAX_AMOUNT]. Because `current` is bounded by
     * [MAX_AMOUNT] the multiply-and-add cannot overflow a [Long], but the check is
     * written defensively so it stays correct even if a caller passes a larger value.
     *
     * @param current the existing running amount (expected in `0..MAX_AMOUNT`).
     * @param digit the digit to append; only its `0..9` meaning is intended.
     * @return the new running amount, never negative and never above [MAX_AMOUNT].
     */
    fun appendDigit(current: Long, digit: Int): Long {
        // If current already exceeds a tenth of the max, appending any digit overflows
        // the ceiling, so short-circuit to the clamp before doing the multiply.
        if (current > MAX_AMOUNT / 10) return MAX_AMOUNT
        val appended = current * 10 + digit
        return if (appended > MAX_AMOUNT) MAX_AMOUNT else appended
    }

    /**
     * Removes the least significant digit from [current] using integer division by 10.
     *
     * Deleting from `0` yields `0`, so the value floors at zero naturally.
     *
     * @param current the existing running amount.
     * @return `current / 10`.
     */
    fun deleteDigit(current: Long): Long = current / 10

    /**
     * Adds [presetValue] to [current], clamped at [MAX_AMOUNT].
     *
     * The sum is computed in an overflow-safe way: if adding would exceed
     * [MAX_AMOUNT] (including the case where a raw [Long] addition would wrap to a
     * negative value) the result is clamped to [MAX_AMOUNT] instead.
     *
     * @param current the existing running amount.
     * @param presetValue the preset chip value to add.
     * @return the new running amount, never above [MAX_AMOUNT].
     */
    fun addPreset(current: Long, presetValue: Long): Long {
        // Reorder to `current > MAX - presetValue` to avoid overflowing during the check.
        if (presetValue > MAX_AMOUNT - current) return MAX_AMOUNT
        return current + presetValue
    }
}
