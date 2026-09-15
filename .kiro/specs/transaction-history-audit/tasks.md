# Implementation Plan: Transaction History & Audit

## Overview

This plan implements Scope 2 (read-only history, filtering, sorting, aggregation, and per-row sync
indicators) as a series of incremental, test-driven Kotlin coding steps that build on each other.
The order follows the design's layering: new read-side data models first, then the pure
input-varying logic (the primary property-test targets), then the read-only DAO queries and
repository, then the `TransactionHistoryViewModel`, then the Compose + Material 3 UI, and finally
wiring everything together into a navigable screen.

The feature **reuses** Scope 1 (`transaction-input-engine`) domain models (`TransactionType`,
`Transaction`, `Wallet`, `Category`), Room entities (`TransactionEntity`, `WalletEntity`,
`CategoryEntity`), and existing DAO/mapper conventions. It adds only read/query capability and
introduces no schema change or migration. The write path defined by Scope 1 is left untouched, and
the read-only boundary is enforced structurally by a repository interface that exposes only
`observe*` methods (Requirement 12, AGENTS.md read-only boundary). Real-time observation is driven
by Room `Flow`s combined into a single `StateFlow<HistoryUiState>` (AGENTS.md Data Rule 2).

Package conventions follow Scope 1:
- Read-side models: `com.alx.moneytracker.domain` (enums/value types shared with the read side).
- Pure logic: `com.alx.moneytracker.domain.logic`.
- Repository: `com.alx.moneytracker.data.repository`.
- DAO additions: `com.alx.moneytracker.data.local.dao`.
- UI: `com.alx.moneytracker.ui.history`.

All amounts remain non-negative `Long` values in the smallest currency unit. Property-based tests
use `kotest-property` (already established by Scope 1), one property test per correctness property,
a minimum of 100 iterations, each tagged with
`// Feature: transaction-history-audit, Property {number}: {property_text}`.

## Tasks

- [x] 1. Add read-side data models
  - Create `SyncStatus` enum (`SYNCED`, `PENDING`) in `com.alx.moneytracker.domain`.
  - Create `SortOrder` enum (`TIME_NEWEST_FIRST`, `TIME_OLDEST_FIRST`, `AMOUNT_LARGEST_FIRST`, `AMOUNT_SMALLEST_FIRST`) with `TIME_NEWEST_FIRST` documented as the default.
  - Create `TimeFilter(startInclusive: Long, endInclusive: Long)`.
  - Create `FilterSet(types, walletIds, categoryIds, syncStatuses, timeFilter)` with all-inactive defaults (empty sets, null time filter) and an `isEmpty` computed property.
  - Create `SummaryTotals(incomeTotal, expenseTotal)` with a `netTotal` computed property (`income - expense`).
  - Reuse Scope 1's `TransactionType`, `Transaction`, `Wallet`, `Category` without modification.
  - _Requirements: 6.2, 8.4, 9.1, 9.2, 10.3, 7.1_

- [x] 2. Implement pure sync-status mapping
  - [x] 2.1 Implement `SyncStatusMapper` in `com.alx.moneytracker.domain.logic`
    - Add `statusOf(isSyncedRaw: Int): SyncStatus` mapping exactly `1` to `SYNCED` and every other value (including `0` and any value outside `{0,1}`) to `PENDING`.
    - _Requirements: 11.1, 11.2, 11.4_

  - [x] 2.2 Write property test for sync-status mapping
    - **Property 8: Sync-status mapping is total and defaults to pending**
    - Generate arbitrary `Int` values; assert `statusOf(1) == SYNCED` and every other value maps to `PENDING`.
    - **Validates: Requirements 11.1, 11.2, 11.4**

- [x] 3. Implement pure filter logic
  - [x] 3.1 Implement `TransactionFilter` in `com.alx.moneytracker.domain.logic`
    - Add `matches(transaction: Transaction, filters: FilterSet): Boolean` composing five per-dimension predicates: type membership, wallet source-OR-destination membership, category membership, mapped sync-status membership (via `SyncStatusMapper`), and inclusive time range where an inverted range (`start > end`) matches nothing.
    - An inactive dimension (empty set / null time filter) imposes no restriction.
    - Add `apply(transactions, filters): List<Transaction>` returning the `Filtered_Set` (OR within a dimension, AND across dimensions); an empty `FilterSet` returns the input unchanged.
    - _Requirements: 3.2, 3.3, 3.4, 4.2, 4.4, 4.5, 5.2, 5.3, 5.4, 6.3, 6.4, 6.5, 6.6, 7.2, 7.3, 7.4, 8.1, 8.2, 8.3, 8.4_

  - [x] 3.2 Write property test for combined filtering
    - **Property 1: Combined filter — OR within a dimension, AND across dimensions**
    - Generate arbitrary transaction lists and `FilterSet`s (each dimension independently active/inactive); assert membership matches an independent brute-force reference predicate (model-based testing), and that an empty `FilterSet` yields the input list.
    - **Validates: Requirements 3.2, 3.3, 3.4, 4.5, 5.2, 5.3, 5.4, 6.3, 6.4, 6.5, 6.6, 7.4, 8.1, 8.2, 8.3, 8.4**

  - [x] 3.3 Write property test for the wallet dimension
    - **Property 2: Wallet filter matches source or destination**
    - Generate transaction lists and non-empty selected wallet-id sets (all other dimensions inactive); assert the `Filtered_Set` is exactly the transactions whose source OR destination wallet is selected.
    - **Validates: Requirements 4.2, 4.4**

  - [x] 3.4 Write property test for the time dimension
    - **Property 3: Time filter is an inclusive range and rejects inverted ranges**
    - Generate transaction lists and `TimeFilter`s including inverted ranges; assert inclusive `start ≤ t ≤ end` matching when `start ≤ end`, and an empty result when `start > end`.
    - **Validates: Requirements 7.2, 7.3**

- [x] 4. Implement pure sort logic
  - [x] 4.1 Implement `TransactionSorter` in `com.alx.moneytracker.domain.logic`
    - Add `sort(transactions, order: SortOrder): List<Transaction>` producing a total order: newest/oldest by timestamp for the time orders; by amount for the amount orders with equal-amount ties broken by descending timestamp.
    - Sorting must be a permutation of the input (no additions/removals) and stable/deterministic.
    - _Requirements: 1.5, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8_

  - [x] 4.2 Write property test for sort ordering
    - **Property 4: Sort order is deterministic with defined tie-breakers**
    - Generate transaction lists (including duplicate timestamps/amounts) and all four `SortOrder` values; assert adjacent-pair ordering including the amount-tie timestamp tie-breaker.
    - **Validates: Requirements 1.5, 9.3, 9.4, 9.5, 9.6**

  - [x] 4.3 Write property test for sort permutation invariance
    - **Property 5: Sorting is a permutation of its input**
    - Generate transaction lists and any `SortOrder`; assert the output is a multiset permutation of the input (same records, same multiplicity), and an empty list sorts to empty.
    - **Validates: Requirements 9.7, 9.8**

- [x] 5. Implement pure summary aggregation
  - [x] 5.1 Implement `SummaryAggregator` in `com.alx.moneytracker.domain.logic`
    - Add `aggregate(transactions): SummaryTotals` summing INCOME amounts and EXPENSE amounts separately, computing net as income minus expense, and excluding TRANSFER from all totals; an empty set yields zeros.
    - _Requirements: 10.2, 10.3, 10.4, 10.7_

  - [x] 5.2 Write property test for summary totals
    - **Property 6: Summary totals sum the filtered set by type**
    - Generate transaction lists; assert income = sum of INCOME amounts, expense = sum of EXPENSE amounts, net = income - expense, and zeros for the empty set.
    - **Validates: Requirements 10.2, 10.3, 10.7**

  - [x] 5.3 Write property test for TRANSFER exclusion
    - **Property 7: Transfers do not affect the summary totals**
    - Metamorphic test: generate a base list and arbitrary TRANSFER records; assert adding the TRANSFERs leaves income, expense, and net totals unchanged.
    - **Validates: Requirements 10.4**

- [x] 6. Implement UI row mapping
  - [x] 6.1 Add `TransactionRowUi` model and row mapper
    - Create `TransactionRowUi(id, type, amount, sourceWalletName, destWalletName?, categoryName, timestamp, syncStatus)` in `com.alx.moneytracker.ui.history`.
    - Implement a pure row mapper (`List<Transaction>.toRows(wallets, categories)`) that resolves wallet/category names, sets `destWalletName` non-null only for TRANSFER, derives `syncStatus` via `SyncStatusMapper`, and falls back to a placeholder label when a referenced wallet/category is missing.
    - _Requirements: 1.2, 1.3, 1.4, 11.1, 11.2, 11.4_

  - [x] 6.2 Write property test for row mapping
    - **Property 10: A rendered row faithfully reflects its transaction**
    - Generate transactions plus wallet/category lookups; assert each row reflects type, amount, source wallet, category, and timestamp, with the destination wallet present exactly for TRANSFER.
    - **Validates: Requirements 1.2, 1.3**

- [x] 7. Checkpoint - pure logic complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Add read-only Room queries (reusing Scope 1 entities)
  - [x] 8.1 Add read-only observe queries to the DAOs
    - Add `@Query("SELECT * FROM transactions") fun observeAll(): Flow<List<TransactionEntity>>` to the existing `TransactionDao` alongside Scope 1's write methods (no ordering/filtering in SQL — done in-memory).
    - Add `@Query("SELECT * FROM categories WHERE is_archived = 0") fun observeAllActive(): Flow<List<CategoryEntity>>` to `CategoryDao`.
    - Reuse `WalletDao.observeActiveWallets()` for wallet chips; add it if not already present.
    - _Requirements: 2.1, 4.1, 5.1_

  - [x] 8.2 Write Room integration test for reactive re-emission
    - Using a Room in-memory database, assert `observeAll()` emits the current list on subscribe, re-emits with an added transaction on insert, re-emits on change, and re-emits excluding a removed transaction (drive inserts via Scope 1's write path).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

- [x] 9. Implement the read-only repository
  - [x] 9.1 Define `HistoryRepository` interface and implementation
    - Define `HistoryRepository` in `com.alx.moneytracker.data.repository` exposing ONLY `observeTransactions(): Flow<Result<List<Transaction>>>`, `observeWallets(): Flow<List<Wallet>>`, and `observeCategories(): Flow<List<Category>>` (no write method — read-only boundary by construction).
    - Implement it over the DAOs, mapping entities to domain models via Scope 1's existing mappers and catching read errors into `Result.failure` on the transactions stream.
    - _Requirements: 1.1, 1.7, 2.1, 4.1, 5.1, 12.1, 12.4_

  - [x] 9.2 Write property test for read isolation
    - **Property 9: Reading, filtering, sorting, and aggregating never mutate the store**
    - Against a Room in-memory database seeded with generated transactions, snapshot the store before and after a sequence of read/filter/sort/aggregate operations; assert the row count and every field value are unchanged.
    - **Validates: Requirements 12.1, 12.2, 12.3**

- [x] 10. Implement the ViewModel
  - [x] 10.1 Implement `TransactionHistoryViewModel`
    - Create `HistoryEvent` sealed interface (type/wallet/category/sync chip toggles, `TimeRangeSelected`, `TimeFilterCleared`, `SortSelected`) in `com.alx.moneytracker.ui.history`.
    - Create `HistoryUiState` (rows, totals, filterSet, sortOrder default `TIME_NEWEST_FIRST`, availableWallets, availableCategories, isError) with `isEmpty` and an `Initial` companion value.
    - Hold `filterSet` and `sortOrder` as `MutableStateFlow`; build `uiState` by `combine`-ing `observeTransactions`, `observeWallets`, `observeCategories`, `filterSet`, and `sortOrder`, applying `TransactionFilter.apply` → `TransactionSorter.sort` → `SummaryAggregator.aggregate` → row mapping, with `flowOn(Dispatchers.IO)` and `stateIn(..., WhileSubscribed(5000), HistoryUiState.Initial)`.
    - On read failure, keep the last good rows/totals and set `isError = true`; never call a write method.
    - Implement `onEvent` to toggle `FilterSet` membership (add on activate, remove on deactivate), set/clear the `TimeFilter`, and replace the single `SortOrder` while preserving the `FilterSet`.
    - _Requirements: 1.1, 1.5, 1.6, 1.7, 2.1, 2.2, 3.4, 4.3, 5.4, 6.2, 6.6, 7.4, 9.2, 9.7, 10.1, 10.5, 10.6, 12.4_

  - [x] 10.2 Write unit tests for ViewModel state and event handling
    - Assert default `SortOrder` is `TIME_NEWEST_FIRST` (9.2) and initial `FilterSet` has both sync statuses inactive and no time filter (6.2); selecting a `SortOrder` leaves exactly one active (9.1); a read-failure emission sets `isError = true` and retains previous rows (1.7, 12.4); changing sort preserves the `FilterSet` (9.7).
    - _Requirements: 6.2, 9.1, 9.2, 9.7, 1.7, 12.4_

- [x] 11. Checkpoint - data + ViewModel complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Implement the Compose UI (Material 3)
  - [x] 12.1 Implement stateless leaf composables
    - `SyncIndicator(status)` — visually distinct minimal icons for SYNCED vs PENDING.
    - `TransactionRow(row)` — renders type, integer smallest-unit amount, source wallet, category, date-time, and the destination wallet only for TRANSFER; includes `SyncIndicator`.
    - `SummaryCard(totals)` — income, expense, and net as separate integer values; zeros when empty.
    - `EmptyState(reason)` and `ErrorState()`.
    - _Requirements: 1.2, 1.3, 1.4, 1.6, 1.7, 10.2, 10.3, 10.7, 11.1, 11.2, 11.4_

  - [x] 12.2 Implement the filter chip header and sort control
    - `FilterChipHeader(state, onEvent)` — horizontally scrollable: three type chips, one chip per available wallet, one chip per available category, SYNCED and PENDING chips, and a time-range chip opening a Material 3 date-range picker; each chip's selected state comes from the `FilterSet` and a tap emits a toggle event.
    - `SortControl(selectedSort, onSortSelected)` — single-choice over the four `SortOrder` values, defaulting to `TIME_NEWEST_FIRST`.
    - _Requirements: 3.1, 4.1, 5.1, 6.1, 7.1, 9.1, 9.2_

  - [x] 12.3 Implement `TransactionLazyColumn` and `HistoryScreen`
    - `TransactionLazyColumn(rows, onEvent)` — `LazyColumn` with stable keys on `Transaction.id` so a single row's change re-renders only that row.
    - `HistoryScreen(state, onEvent)` — stateless; renders the filter chip header and sort control on top, the summary card below, then the list or the empty/error state, forwarding user actions as `HistoryEvent`s.
    - _Requirements: 1.1, 1.6, 1.7, 11.3_

  - [x] 12.4 Write Compose UI tests
    - Assert chip composition (three type chips, one per wallet, one per category, SYNCED + PENDING, a time chip); empty-state rendering with chip selections retained; error-state rendering without clearing the prior list; SYNCED vs PENDING indicators render visually distinct and a single `is_synced` change updates only its row; summary card shows income/expense/net separately.
    - _Requirements: 3.1, 4.1, 5.1, 6.1, 7.1, 1.6, 1.7, 10.2, 10.3, 11.1, 11.2, 11.3_

- [x] 13. Wire the history screen into the app
  - [x] 13.1 Construct and connect the dependency graph
    - Provide `HistoryRepository` from the existing Room database/`DatabaseProvider`, construct `TransactionHistoryViewModel` (factory or existing DI convention), collect `uiState` in `HistoryScreen`, and add navigation/entry into the history screen alongside the Scope 1 input screen.
    - _Requirements: 1.1, 2.1_

  - [x] 13.2 Write Room integration tests for reactive recomputation and read isolation end-to-end
    - Against a Room in-memory database with the wired repository/ViewModel: after a Scope 1 insert, assert the observed list re-emits and the summary recomputes over the resulting `Filtered_Set` (2.1, 2.2, 2.3, 10.5, 10.6); a change re-emits (2.4); a removal re-emits excluding it (2.5); and observing/filtering/sorting/aggregating leaves the table row count and field values unchanged (12.1, 12.2, 12.3).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 10.5, 10.6, 12.1, 12.2, 12.3_

- [x] 14. Final checkpoint - ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation sub-tasks are never optional.
- Each task references specific requirements for traceability.
- Property-based tests use `kotest-property`, one test per property, a minimum of 100 iterations, each tagged with `// Feature: transaction-history-audit, Property {number}: {property_text}`.
- Property 1 is cross-checked against an independent brute-force reference predicate (model-based testing); Property 9 runs against a Room in-memory database.
- Properties 1–8 and 10 target pure logic; Property 9 targets read isolation at the real-DB boundary.
- UI composition, empty/error rendering, OS date-picker behavior, `StateFlow` exposure, and Room reactive re-emission are covered by Compose UI tests and Room integration tests rather than property tests, since they do not vary meaningfully with input.
- The feature adds no schema change or migration and reuses Scope 1 entities, domain models, and mappers; the read-only boundary is enforced structurally by the repository interface (Requirement 12).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "8.1"] },
    { "id": 1, "tasks": ["2.1", "3.1", "4.1", "5.1", "8.2"] },
    { "id": 2, "tasks": ["2.2", "3.2", "3.3", "3.4", "4.2", "4.3", "5.2", "5.3", "6.1", "9.1"] },
    { "id": 3, "tasks": ["6.2", "9.2", "10.1"] },
    { "id": 4, "tasks": ["10.2", "12.1", "12.2"] },
    { "id": 5, "tasks": ["12.3"] },
    { "id": 6, "tasks": ["12.4", "13.1"] },
    { "id": 7, "tasks": ["13.2"] }
  ]
}
```
