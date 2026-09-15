package com.alx.moneytracker.domain.logic

import com.alx.moneytracker.domain.SyncStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Property-based test for [SyncStatusMapper] (Task 2.2).
 *
 * Exactly one property test covering Property 8, using kotest-property with a minimum
 * of 100 iterations. Generates arbitrary [Int] values across the full input space so
 * that `0`, `1`, and values outside `{0, 1}` (negative and large) are all exercised.
 *
 * Validates: Requirements 11.1, 11.2, 11.4
 */
class SyncStatusMapperPropertyTest : FunSpec({

    // The full Int input space so the mapping's totality is exercised, including 0, 1,
    // negatives, and values outside {0, 1}.
    val rawValues: Arb<Int> = Arb.int()

    // Feature: transaction-history-audit, Property 8: Sync-status mapping is total and defaults to pending
    test("Property 8: Sync-status mapping is total and defaults to pending") {
        checkAll(PropTestConfig(iterations = 100), rawValues) { raw ->
            val expected = if (raw == 1) SyncStatus.SYNCED else SyncStatus.PENDING
            SyncStatusMapper.statusOf(raw) shouldBe expected
        }
    }
})
