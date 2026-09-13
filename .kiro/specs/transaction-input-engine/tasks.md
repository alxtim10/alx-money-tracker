# Implementation Plan: Transaction Input Engine

## Overview

This plan implements the Transaction Input Engine in Kotlin for Android using the MVVM stack defined in the design: Room entities/DAOs, a `TransactionRepository`, pure logic helpers (`AmountReducer`, `SubmitValidator`, and delta computation), a `TransactionInputViewModel` exposing an immutable `StateFlow`, and stateless Jetpack Compose (Material 3) UI wired together at the end.

Work proceeds bottom-up so functionality is validated early: data layer → repository → pure logic → ViewModel → Compose UI → wiring. The 13 correctness properties from the design are implemented with kotest-property (one property-based test per property, minimum 100 iterations, each tagged `// Feature: transaction-input-engine, Property {n}: {text}`). Room in-memory integration tests cover atomicity/rollback/Flow emission; Compose UI tests cover keyboard suppression, layout, and rendering; unit tests cover edge cases.

Convention: amounts are `Long` values in the smallest currency unit. `@Transaction` is the atomic boundary. Wallet/category lists and balances are observed via Room `Flow` surfaced as `StateFlow`. Optional test sub-tasks are marked with `*`.

## Tasks

- [x] 1. Establish domain models, enums, and pure logic scaffolding
  - [x] 1.1 Define domain models and `TransactionType` enum
    - Create `TransactionType { EXPENSE, INCOME, TRANSFER }`
    - Create `Transaction`, `Wallet`, `Category`, `QuickPreset` data classes with `Long` amounts/balances
    - Place in a `domain` package so they are Android-independent and testable on the JVM
    - _Requirements: 3.1, 10.3, 10.4, 10.5_

  - [x] 1.2 Implement `AmountReducer` pure helper
    - Implement `appendDigit(current, digit)` = `current * 10 + digit`, clamped at `MAX_AMOUNT`
    - Implement `deleteDigit(current)` = integer division by 10, floored at 0
    - Implement `addPreset(current, presetValue)` = `current + presetValue`, clamped at `MAX_AMOUNT`
    - Define `MAX_AMOUNT = 999_999_999_999L`
    - _Requirements: 1.2, 1.3, 1.4, 2.2, 2.3_

  - [x] 1.3 Write property tests for `AmountReducer`
    - **Property 1: Digit append multiplies and adds** — `// Feature: transaction-input-engine, Property 1`
    - **Property 2: Digit delete drops the least significant digit** — `// Feature: transaction-input-engine, Property 2`
    - **Property 3: Preset taps accumulate additively** — `// Feature: transaction-input-engine, Property 3`
    - kotest-property, min 100 iterations each; generators over amounts `0..MAX_AMOUNT`, digits `0..9`, lists of preset values
    - **Validates: Requirements 1.2, 1.3, 1.4, 2.2, 2.3**

  - [x] 1.4 Write unit tests for `AmountReducer` edge cases
    - Explicit `deleteDigit(0) == 0`; append/preset clamp at `MAX_AMOUNT`
    - _Requirements: 1.4, 2.2_

- [x] 2. Implement Room data layer (entities + database)
  - [x] 2.1 Define Room entities
    - `TransactionEntity` (String PK id, timestamp, type, amount, source_wallet, dest_wallet nullable, category_id, note, is_synced Int)
    - `WalletEntity`, `CategoryEntity`, `QuickPresetEntity` with `is_archived` flags where specified
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 11.1_

  - [x] 2.2 Implement DAOs and the atomic `@Transaction` method
    - `TransactionDao` with `insert`, `applyBalanceDelta(walletId, delta)`, and `@Transaction insertAndApplyBalances(tx, sourceWalletId, sourceDelta, destWalletId?, destDelta)`
    - `WalletDao.observeActiveWallets()`, `WalletDao.observeDefaultWallet()`, `CategoryDao.observeByType(type)`, `QuickPresetDao.observeAll()` returning `Flow`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 12.1_

  - [x] 2.3 Create Room `AppDatabase` and mappers
    - Register entities/DAOs; add entity↔domain mapping functions
    - _Requirements: 12.1_

  - [x] 2.4 Write Room in-memory integration tests for atomicity and Flow
    - Atomic write commits insert + balance update together (9.1)
    - Induced-failure test: a failing balance update rolls back the insert, leaving both stores unchanged (9.5)
    - `observeActiveWallets()` emits updated balance after a wallet update (12.1, 12.2)
    - _Requirements: 9.1, 9.5, 12.1, 12.2_

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement the repository
  - [x] 4.1 Implement `TransactionRepository` interface and Room-backed implementation
    - `observeWallets()`, `observeCategories(type)`, `observeQuickPresets()`, `observeDefaultWallet()` delegating to DAO `Flow`s
    - `saveTransaction(transaction)` computes deltas (EXPENSE `-amount`; INCOME `+amount`; TRANSFER `-amount`/`+amount`), calls `insertAndApplyBalances`, maps exceptions to `Result.failure`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 12.1_

  - [x] 4.2 Write property tests for balance-delta logic (Room in-memory / fake DAO)
    - **Property 8: Balance deltas are correct for each transaction type** — `// Feature: transaction-input-engine, Property 8`
    - **Property 9: Transfers conserve combined balance** — `// Feature: transaction-input-engine, Property 9`
    - kotest-property, min 100 iterations; generators over balances and positive amounts
    - **Validates: Requirements 9.2, 9.3, 9.4**

  - [x] 4.3 Write unit test for repository error mapping
    - A thrown DAO exception is mapped to `Result.failure` (no raw exception escapes)
    - _Requirements: 9.5_

- [x] 5. Implement UI state, validation, and selection logic
  - [x] 5.1 Define `TransactionInputUiState` and `TransactionInputEvent`
    - Immutable state with defaults (type = EXPENSE, empty note); computed `isSubmitEnabled`
    - Sealed `TransactionInputEvent` (DigitPressed, DeletePressed, PresetTapped, TypeSelected, SourceWalletSelected, DestWalletSelected, CategorySelected, NoteChanged, Submit, ErrorConsumed)
    - _Requirements: 3.2, 6.4_

  - [x] 5.2 Implement `SubmitValidator` and selection/filter helpers
    - `isSubmitEnabled`: `amount > 0` AND category selected AND (type != TRANSFER OR dest selected and differs from source)
    - Category filter (non-archived, matching selected type); source/dest wallet filters (non-archived; dest excludes source)
    - _Requirements: 4.2, 4.3, 4.4, 5.2, 5.3, 7.1, 7.2, 7.3_

  - [x] 5.3 Write property test for submit gating
    - **Property 4: Submit control is enabled exactly when the entry is valid** — `// Feature: transaction-input-engine, Property 4`
    - kotest-property, min 100 iterations; generate full `TransactionInputUiState` values
    - **Validates: Requirements 7.1, 7.2, 7.3, 4.4**

  - [x] 5.4 Write property tests for category/wallet/selection logic
    - **Property 5: Presented categories match the selected type** — `// Feature: transaction-input-engine, Property 5`
    - **Property 6: Type and category selection are single-valued** — `// Feature: transaction-input-engine, Property 6`
    - **Property 7: Selectable wallet sets exclude archived and self** — `// Feature: transaction-input-engine, Property 7`
    - kotest-property, min 100 iterations; generate lists of `Category`/`Wallet` with random archived flags and types
    - **Validates: Requirements 5.2, 5.3, 3.1, 5.1, 4.2, 4.3**

- [x] 6. Implement the ViewModel
  - [x] 6.1 Implement `TransactionInputViewModel`
    - Inject `repository`, `clock`, and `idGenerator` for deterministic identity/timestamp
    - Expose `uiState: StateFlow`; collect wallets/categories/presets/default wallet Flows into state (set source to default on load)
    - Handle events: numpad via `AmountReducer`, preset accumulation, type/wallet/category/note selection
    - _Requirements: 1.2, 1.3, 1.4, 2.2, 2.3, 2.4, 3.1, 3.3, 4.1, 5.1, 5.4, 6.1, 6.4, 12.1_

  - [x] 6.2 Implement submit orchestration and post-save reset
    - Re-check `SubmitValidator` before building; build `Transaction` (UUID id, epoch-millis timestamp, is_synced=0, note "" when empty)
    - Run persistence in `viewModelScope` on `Dispatchers.IO`; on success reset amount=0 and category=none (preserve type/wallets); on failure keep state and set `errorMessage`
    - _Requirements: 6.4, 9.1, 9.5, 10.1, 10.2, 10.3, 10.4, 10.5, 11.1, 12.3_

  - [x] 6.3 Write property tests for transaction construction and reset
    - **Property 10: Persisted transaction faithfully records the input state** — `// Feature: transaction-input-engine, Property 10`
    - **Property 11: Every persisted transaction has a unique, non-empty id** — `// Feature: transaction-input-engine, Property 11`
    - **Property 12: Every persisted transaction is marked unsynced** — `// Feature: transaction-input-engine, Property 12`
    - **Property 13: A successful save resets amount and category** — `// Feature: transaction-input-engine, Property 13`
    - kotest-property, min 100 iterations; generate valid input states, inject `clock`/`idGenerator`, assert field mapping, id uniqueness across sequences, sync flag, post-save reset
    - **Validates: Requirements 10.1, 10.3, 10.4, 10.5, 11.1, 12.3**

  - [x] 6.4 Write unit tests for ViewModel state defaults
    - Default type is EXPENSE (3.2); source wallet set to default on load (4.1); empty note persists as "" (6.4); no-default-wallet keeps Submit disabled
    - _Requirements: 3.2, 4.1, 6.4_

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Implement Compose UI (stateless, one-handed layout)
  - [x] 8.1 Implement child composables
    - `AmountDisplay` (non-focusable, integer smallest-unit formatting), `CustomNumpad` (0-9 + delete, no OS keyboard), `QuickPresetChips`, `TransactionTypeSelector`, `WalletSelector` (dest only for TRANSFER), `CategorySelector`, `NoteField` (OS keyboard only on focus), `SubmitButton` (enabled from state)
    - _Requirements: 1.1, 1.5, 2.1, 2.4, 3.3, 4.3, 5.4, 6.1, 6.2, 6.3, 7.1_

  - [x] 8.2 Implement `TransactionInputScreen` with lower-region layout
    - Render state; forward actions as events; place numpad, chips, and submit control in the lower region for thumb reach
    - _Requirements: 8.1, 8.2_

  - [x] 8.3 Write Compose UI tests
    - Numpad shown and amount field non-focusable so OS keyboard not requested for numbers (1.1); amount uses integer formatting (1.5)
    - Preset set renders (2.1); active type/category render (3.3, 5.4); updated amount shown after input (2.4)
    - Note field keeps keyboard hidden until focused, shows on tap, accepts free text (6.1, 6.2, 6.3)
    - Numpad and submit control bounds fall in the lower region (8.1, 8.2)
    - _Requirements: 1.1, 1.5, 2.1, 2.4, 3.3, 5.4, 6.1, 6.2, 6.3, 8.1, 8.2_

- [x] 9. Wire the feature together
  - [x] 9.1 Assemble screen, ViewModel, repository, and database
    - Provide dependencies (DB → DAOs → repository → ViewModel), connect `uiState` to `TransactionInputScreen`, route events to `onEvent`, surface `errorMessage` (e.g. snackbar) and dispatch `ErrorConsumed`
    - _Requirements: 12.1, 12.2, 12.3, 9.5_

  - [x] 9.2 Write integration test for the end-to-end save path
    - With an in-memory database: a valid submit persists an unsynced transaction, applies balances atomically, emits the updated balance via Flow, and resets amount/category
    - _Requirements: 9.1, 11.1, 12.2, 12.3_

- [x] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test tasks and can be skipped for a faster MVP.
- Each task references specific requirements for traceability.
- Checkpoints ensure incremental validation.
- Property-based tests (Properties 1-13) use kotest-property with a minimum of 100 iterations and are tagged with `// Feature: transaction-input-engine, Property {number}: {property_text}`; each property has exactly one property-based test placed close to the code it validates.
- UI positioning, OS-keyboard suppression, and Room transactionality are covered by Compose UI tests and Room integration tests rather than property tests, since they do not vary meaningfully with input.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["1.2", "2.2"] },
    { "id": 2, "tasks": ["1.3", "1.4", "2.3", "2.4"] },
    { "id": 3, "tasks": ["4.1", "5.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "5.2"] },
    { "id": 5, "tasks": ["5.3", "5.4", "6.1"] },
    { "id": 6, "tasks": ["6.2", "8.1"] },
    { "id": 7, "tasks": ["6.3", "6.4", "8.2"] },
    { "id": 8, "tasks": ["8.3", "9.1"] },
    { "id": 9, "tasks": ["9.2"] }
  ]
}
```
