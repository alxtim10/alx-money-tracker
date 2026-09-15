package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.SyncStatus

/**
 * Pure mapping from a raw `is_synced` integer to a [SyncStatus].
 *
 * The mapping is total: every possible [Int] input yields a well-defined status.
 * A value of exactly `1` maps to [SyncStatus.SYNCED]; every other value — including
 * `0` and any value outside `{0, 1}` — maps to [SyncStatus.PENDING]. This guarantees
 * a displayed row always has a defined synchronization indicator rather than an
 * undefined state (Requirements 11.1, 11.2, 11.4).
 *
 * This object holds no Android dependencies so it can be exercised directly on the
 * JVM by unit and property tests.
 */
object SyncStatusMapper {

    /**
     * Maps a raw `is_synced` value to its [SyncStatus].
     *
     * @param isSyncedRaw the raw `is_synced` value read from a transaction.
     * @return [SyncStatus.SYNCED] when [isSyncedRaw] is exactly `1`, otherwise
     *   [SyncStatus.PENDING].
     */
    fun statusOf(isSyncedRaw: Int): SyncStatus =
        if (isSyncedRaw == 1) SyncStatus.SYNCED else SyncStatus.PENDING
}
