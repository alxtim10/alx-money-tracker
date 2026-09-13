package com.alx.moneytracker.domain

/**
 * The kind of financial movement a [Transaction] records.
 *
 * - [EXPENSE]: money leaving the source wallet.
 * - [INCOME]: money entering the source wallet.
 * - [TRANSFER]: money moving from the source wallet to a destination wallet.
 *
 * Kept in the Android-independent `domain` package so it is testable on the JVM.
 */
enum class TransactionType {
    EXPENSE,
    INCOME,
    TRANSFER
}
