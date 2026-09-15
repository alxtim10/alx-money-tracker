package com.alx.moneytracker.domain

/**
 * An inclusive time range for the Time_Filter dimension, expressed in epoch milliseconds.
 *
 * A range with [startInclusive] greater than [endInclusive] is treated as matching no
 * Transaction records (Requirement 7.3).
 *
 * @property startInclusive Range start (inclusive), epoch millis.
 * @property endInclusive Range end (inclusive), epoch millis.
 */
data class TimeFilter(
    val startInclusive: Long,
    val endInclusive: Long
)
