package com.alx.moneytracker.domain

/**
 * The ordering applied to the Transaction_List.
 *
 * - [TIME_NEWEST_FIRST]: by timestamp, descending. The default Sort_Order (Requirement 9.2).
 * - [TIME_OLDEST_FIRST]: by timestamp, ascending.
 * - [AMOUNT_LARGEST_FIRST]: by amount, descending; equal amounts broken by descending timestamp.
 * - [AMOUNT_SMALLEST_FIRST]: by amount, ascending; equal amounts broken by descending timestamp.
 *
 * Kept in the Android-independent `domain` package so it is testable on the JVM.
 */
enum class SortOrder {
    /** Default Sort_Order applied when the history screen is first displayed (Requirement 9.2). */
    TIME_NEWEST_FIRST,
    TIME_OLDEST_FIRST,
    AMOUNT_LARGEST_FIRST,
    AMOUNT_SMALLEST_FIRST
}
