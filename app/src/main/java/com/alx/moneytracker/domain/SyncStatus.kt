package com.alx.moneytracker.domain

/**
 * The synchronization state of a [Transaction], derived from its `is_synced` value.
 *
 * - [SYNCED]: the record has reached the backend (`is_synced` == 1).
 * - [PENDING]: the record has not yet been transmitted (`is_synced` == 0, or any value
 *   outside `{0, 1}`).
 *
 * Kept in the Android-independent `domain` package so it is testable on the JVM.
 */
enum class SyncStatus {
    SYNCED,
    PENDING
}
