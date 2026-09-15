# Implementation Plan: Customization & Metadata

## Overview

This plan implements Scope 3 (management of Wallets, Categories, and Quick Presets — the metadata
that Scope 1 and Scope 2 consume) as a series of incremental, test-driven Kotlin coding steps that
build on each other. The order follows the design's layering: the write-side value types and pure
input-varying logic (the primary property-test targets) first, then the added Room DAO methods on
the existing metadata tables, then the `MetadataRepository`, then the three per-surface ViewModels,
then the Compose + Material 3 management screens (reusing Scope 1's `CustomNumpad`), and finally
wiring everything together into navigable screens.

The feature **reuses** Scope 1 & 2 domain models (`TransactionType`, `Wallet`, `Category`,
`QuickPreset`), Room entities (`WalletEntity`, `CategoryEntity`, `QuickPresetEntity`), and the
existing `toDomain()` mappers. It introduces **no schema change and no migration** — the
`is_archived` columns already exist. It adds only management capability: CRUD/archive/override/
default DAO methods, a `MetadataRepository`, ViewModels, and Compose screens.

The defining constraint (AGENTS.md **Data Rule 3**): a `Direct_Balance_Override` is a single-row
`UPDATE wallets SET balance = :value` with **no** `transactions` insert. This feature must **never**
mutate the `transactions` table under any operation. The boundary is enforced structurally: the
`MetadataRepository` and the metadata DAOs have **no `TransactionDao` dependency and no access to the
`transactions` table**. **Data Rule 1**: atomic default assignment and archive-with-reassignment use
`@Transaction` DAO methods. **Data Rule 2**: all three collections are exposed as Room `Flow`s so
changes propagate live into Scope 1 & 2.

Package conventions follow Scope 1 & 2:
- Write-side value types + pure logic: `com.alx.moneytracker.domain` and `com.alx.moneytracker.domain.logic`.
- Repository: `com.alx.moneytracker.data.repository`.
- DAO additions: `com.alx.moneytracker.data.local.dao`.
- UI: `com.alx.moneytracker.ui.metadata` (three sub-screens for wallet/category/preset management).

All balances and amounts remain non-negative `Long` values in the smallest currency unit.
Property-based tests use `kotest-property` (already established by Scope 1 & 2), one property test
per correctness property, a minimum of 100 iterations, each tagged with
`// Feature: customization-metadata, Property {number}: {property_text}`.

## Tasks

- [x] 1. Add write-side value types and validation result types
  - Create `ValidationResult<out T>` sealed interface (`Valid<T>(value)`, `Invalid(reason)`) in `com.alx.moneytracker.domain`.
  - Create `ValidationError` enum (`NAME_EMPTY`, `NAME_TOO_LONG`, `BALANCE_OUT_OF_RANGE`, `AMOUNT_OUT_OF_RANGE`, `LABEL_EMPTY`, `LABEL_TOO_LONG`, `TYPE_NOT_PERMITTED`).
  - Create `NewWallet(name, balance)`, `CategoryFields(name, type, icon?)`, `CategoryPatch(name? = null, type? = null, icon? = null)`, `PresetFields(amount, label)`, `PresetPatch(amount? = null, label? = null)`.
  - Reuse Scope 1's `TransactionType`, `Wallet`, `Category`, `QuickPreset` without modification.
  - _Requirements: 1.1, 4.1, 7.1, 9.1, 11.1, 13.1_

- [x] 2. Implement pure metadata validation
  - [x] 2.1 Implement `MetadataValidator` in `com.alx.moneytracker.domain.logic`
    - Add constants `MAX_MONEY = 999_999_999_999L`, `WALLET_NAME_MAX = 100`, `CATEGORY_NAME_MAX = 50`, `PRESET_LABEL_MAX = 50`.
    - `validateWalletCreate(rawName, balance): ValidationResult<NewWallet>` — trims name, valid when 1..100 chars and balance in `0..MAX_MONEY`; returns `Invalid(NAME_EMPTY/NAME_TOO_LONG)` or `Invalid(BALANCE_OUT_OF_RANGE)` otherwise.
    - `validateWalletName(rawName): ValidationResult<String>` — trims, valid when 1..100 chars.
    - `validateBalance(balance): ValidationResult<Long>` — valid when balance in `0..MAX_MONEY`.
    - `validateCategory(rawName, type): ValidationResult<CategoryFields>` — trims name, valid when 1..50 chars AND `type != TRANSFER`; returns `Invalid(TYPE_NOT_PERMITTED)` for TRANSFER.
    - `validatePreset(amount, rawLabel): ValidationResult<PresetFields>` — valid when amount in `1..MAX_MONEY` AND trimmed label in 1..50 chars.
    - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.2, 4.1, 4.2, 7.1, 7.2, 7.3, 9.2, 9.3, 11.1, 11.2, 11.3, 13.2, 13.3_

  - [x] 2.2 Write property test for validation
    - **Property 8: Validation accepts valid input and rejects invalid input without mutating any store**
    - Generators over names/labels (valid 1..bound including surrounding whitespace to exercise trimming; invalid empty/all-whitespace/over-bound), balances/amounts (in-range and out-of-range negative/`> MAX`), and `TransactionType` (including TRANSFER). Assert the iff acceptance characterization and that the returned `Invalid` identifies the specific offending field.
    - **Validates: Requirements 1.1, 1.2, 1.3, 3.1, 3.2, 4.1, 4.2, 7.1, 7.2, 7.3, 9.2, 9.3, 11.1, 11.2, 11.3, 13.2, 13.3**

- [x] 3. Implement deterministic default-wallet replacement selection
  - [x] 3.1 Implement `DefaultWalletResolver` in `com.alx.moneytracker.domain.logic`
    - Add `pickReplacementDefault(remainingActive: List<Wallet>): Long?` returning the id of the wallet whose name sorts first case-insensitively, ties broken by lowest id; `null` when the list is empty.
    - _Requirements: 5.5, 5.6_

  - [x] 3.2 Write property test for replacement selection
    - **Property 5: Default reassignment on archive selects deterministically**
    - Generate active-wallet sets (including case variants and duplicate names) and cross-check the result against an independent reference selection; assert `null` for the empty set.
    - **Validates: Requirements 5.5, 5.6**

- [x] 4. Checkpoint - pure logic complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Add wallet management DAO methods (reusing Scope 1 entities)
  - [x] 5.1 Add write/override/archive/default methods to `WalletDao`
    - Alongside Scope 1's `observeActiveWallets()` / `observeDefaultWallet()`, add: `@Insert suspend fun insert(wallet: WalletEntity): Long`; `updateName(id, name)`; `overrideBalance(id, balance)` as a single `UPDATE wallets SET balance = :balance WHERE id = :id` (NO transactions insert — Data Rule 3); `markArchived(id)`; `activeCount()`; `findById(id)`; `clearAllDefaults()`; `setDefault(id)`.
    - Add `@Transaction suspend fun assignDefault(id)` = `clearAllDefaults(); setDefault(id)` (Data Rule 1).
    - Add `@Transaction suspend fun archiveAndReassignDefault(id, replacementId?)` = `markArchived(id)` then, if `replacementId != null`, `clearAllDefaults(); setDefault(replacementId)`.
    - No reference to `TransactionDao` or the `transactions` table.
    - _Requirements: 1.1, 3.1, 4.1, 5.1, 5.5, 5.6, 6.1, 6.2_

  - [x] 5.2 Write Room integration test for override vs. Scope 1 contrast and default atomicity
    - Using a Room in-memory database: assert `overrideBalance` performs a single `wallets` update and inserts no `transactions` row, whereas Scope 1's `insertAndApplyBalances` inserts a row (paths distinct, 4.3, 4.4); assert `assignDefault` and `archiveAndReassignDefault` leave exactly one active default and an induced mid-`@Transaction` failure rolls back so the prior default is retained (6.1, 6.2, 6.5, 5.5).
    - _Requirements: 4.3, 4.4, 5.5, 6.1, 6.2, 6.5_

- [x] 6. Add category and preset management DAO methods
  - [x] 6.1 Add write/archive methods to `CategoryDao`
    - Add `@Insert suspend fun insert(category: CategoryEntity): Long`; `@Update suspend fun update(category: CategoryEntity)`; `findById(id)`; `markArchived(id)` as `UPDATE categories SET is_archived = 1 WHERE id = :id`. No `transactions` reference.
    - _Requirements: 7.1, 9.1, 10.1_

  - [x] 6.2 Add write/delete methods to `QuickPresetDao`
    - Alongside Scope 1's `observeAll()`, add `@Insert suspend fun insert(preset: QuickPresetEntity): Long`; `@Update suspend fun update(preset: QuickPresetEntity)`; `deleteById(id)` as a hard delete `DELETE FROM quick_presets WHERE id = :id`; `findById(id)`. No `transactions` reference.
    - _Requirements: 11.1, 13.1, 14.1_

- [x] 7. Implement the metadata repository
  - [x] 7.1 Define `MetadataRepository` interface and implementation
    - Define `MetadataRepository` in `com.alx.moneytracker.data.repository` exposing observation `observeWallets(): Flow<List<Wallet>>` (Active_Wallet only), `observeCategories(): Flow<List<Category>>` (Active_Category only), `observeQuickPresets(): Flow<List<QuickPreset>>`, plus suspend writes returning `Result<Unit>`: `createWallet`, `renameWallet`, `overrideBalance`, `archiveWallet`, `setDefaultWallet`, `createCategory`, `updateCategory`, `archiveCategory`, `createPreset`, `updatePreset`, `deletePreset`. No transaction-write method and NO `TransactionDao` dependency (Requirement 15 by construction).
    - Implement over the metadata DAOs using Scope 1's `toDomain()` mappers. `createWallet` resolves `is_default` from `activeCount()` (first active wallet becomes default, else false). `archiveWallet` reads the target (not-found → `Result.failure`), computes the replacement via `DefaultWalletResolver` when archiving the current default, and delegates to `archiveAndReassignDefault`; an already-archived target is a no-op. `setDefaultWallet` guards existence + not-archived then delegates to `assignDefault`; designating the current default is a no-op. `overrideBalance` delegates to the single-row balance update. Each write catches thrown exceptions into `Result.failure`, leaving the affected store in its prior state.
    - _Requirements: 1.4, 1.5, 2.1, 2.2, 2.3, 2.4, 3.1, 3.3, 4.1, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.4, 8.1, 8.2, 8.3, 8.4, 9.1, 9.4, 9.5, 10.1, 10.2, 10.3, 10.4, 10.5, 11.1, 12.1, 12.2, 12.3, 12.4, 13.1, 14.1, 14.2, 14.3, 15.1, 15.2, 15.3, 15.4_

  - [x] 7.2 Write property test: no metadata operation mutates the transactions store
    - **Property 1: No metadata operation ever mutates the transactions store**
    - Against a Room in-memory database seeded with generated transactions, run each generated metadata operation (create/rename/update/override/archive/delete/set-default); snapshot the `transactions` table before and after and assert the row count and every field value are unchanged (including that an override adds no row).
    - **Validates: Requirements 4.3, 4.4, 5.4, 9.5, 10.4, 14.2, 15.1, 15.2, 15.3**

  - [x] 7.3 Write property test: archiving is a soft delete that preserves the record
    - **Property 2: Archiving is a soft delete that preserves the record**
    - Generate non-archived wallets/categories; assert archiving flips `is_archived` to true, retains the row (count unchanged, retrievable by id), and excludes it from the active `Observable_Stream`.
    - **Validates: Requirements 5.1, 5.2, 10.1, 10.2**

  - [x] 7.4 Write property test: archiving an already-archived record is a no-op
    - **Property 3: Archiving an already-archived record is a no-op**
    - Generate already-archived wallets/categories; assert the store, the `Default_Wallet` designation, and the `transactions` store are unchanged.
    - **Validates: Requirements 5.7, 10.5**

  - [x] 7.5 Write property test: exactly-one-default invariant
    - **Property 4: Exactly one active wallet is the default whenever an active wallet exists**
    - Apply generated sequences of wallet operations (create/set-default/archive) against a Room in-memory database; assert after each step exactly one `Active_Wallet` has `is_default = true` when an active wallet exists (none otherwise), including create-when-empty, idempotent redesignation, and archived-target rejection with prior default retained.
    - **Validates: Requirements 1.5, 6.1, 6.2, 6.3, 6.4**

  - [x] 7.6 Write property test: override overwrites only the target balance
    - **Property 6: Direct Balance Override overwrites only the target balance**
    - Generate multi-wallet stores and target balances in `0..MAX_MONEY`; assert the target wallet's balance is set exactly (overwrite, not delta), its name/`is_default`/`is_archived` are unchanged, and every other wallet's balance is unchanged.
    - **Validates: Requirements 4.1, 4.5, 4.6**

  - [x] 7.7 Write property test: renaming a wallet changes only its name
    - **Property 7: Renaming a wallet changes only its name**
    - Generate wallets and valid new names; assert the trimmed name persists and balance/`is_default`/`is_archived` are unchanged.
    - **Validates: Requirements 3.1, 3.3**

  - [x] 7.8 Write property test: partial update changes only submitted fields
    - **Property 9: A partial update changes only the submitted fields**
    - Generate categories/presets and valid partial updates with omitted (null) fields; assert only the submitted (validated, trimmed) fields change and every omitted field — including a category's `is_archived` — is unchanged.
    - **Validates: Requirements 9.1, 9.4, 13.1**

  - [x] 7.9 Write property test: valid creation persists exactly the submitted fields
    - **Property 10: A valid creation persists exactly the submitted fields**
    - Generate valid wallet/category/preset submissions; assert the persisted record carries the trimmed name/label, submitted balance/amount, submitted type/icon (category), and `is_archived = false` (wallet/category).
    - **Validates: Requirements 1.1, 7.1, 11.1**

  - [x] 7.10 Write property test: deleting a preset removes only that preset
    - **Property 11: Deleting a preset removes only that preset**
    - Generate multi-preset stores; assert deleting one removes exactly that record (count decreases by one, no longer retrievable) and leaves every other preset unchanged.
    - **Validates: Requirements 14.1, 14.3**

  - [x] 7.11 Write property test: entity-to-domain mapping is faithful
    - **Property 12: Entity-to-domain mapping is faithful**
    - Generate `WalletEntity`/`CategoryEntity`/`QuickPresetEntity`; assert `toDomain()` preserves every field (wallet: name/balance/`is_default`/`is_archived`; category: name/type/icon/`is_archived`; preset: amount/label).
    - **Validates: Requirements 2.3, 8.3, 12.3**

  - [x] 7.12 Write unit tests for repository edge cases and failure mapping
    - Assert create-wallet with a null initial balance persists `0` (1.4); create-category with no icon persists a null icon (7.4); archive of a non-existent wallet returns `Result.failure` (not found) with the store unchanged (5.8); a thrown DAO exception maps to `Result.failure` on each write path with the affected store retained (4.7, 6.5, 15.4).
    - _Requirements: 1.4, 5.8, 6.5, 7.4, 4.7, 15.4_

- [x] 8. Checkpoint - data + repository complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement the wallet management ViewModel
  - [x] 9.1 Implement `WalletManagementViewModel`
    - Create `WalletManagementEvent` sealed interface (`CreateWallet(name, initialBalance?)`, `RenameWallet`, `OverrideBalance`, `ArchiveWallet`, `SetDefaultWallet`, `ErrorConsumed`) and `WalletManagementUiState(wallets, error?, operationFailed)` with an `Initial` companion in `com.alx.moneytracker.ui.metadata`.
    - Build `uiState` from `repository.observeWallets()` with `flowOn(Dispatchers.IO)` and `stateIn(..., WhileSubscribed(5000), Initial)`. In `onEvent`, validate via `MetadataValidator` before persisting (null initial balance → `0L` before validation); map `Invalid` to `state.error`, map repository `Result.failure` to `operationFailed`. Never touch `transactions`.
    - _Requirements: 1.2, 1.3, 1.4, 2.1, 2.4, 3.2, 4.2, 4.7, 6.4, 6.5_

  - [x] 9.2 Write unit tests for wallet ViewModel state and event handling
    - Assert invalid name/balance sets `state.error` and does not call the repository (store unchanged); null initial balance is treated as `0` (1.4); a repository failure sets `operationFailed`; `ErrorConsumed` clears the error.
    - _Requirements: 1.2, 1.3, 1.4, 4.2, 4.7, 6.5_

- [x] 10. Implement the category and preset management ViewModels
  - [x] 10.1 Implement `CategoryManagementViewModel`
    - Create `CategoryManagementEvent` (`CreateCategory(name, type, icon?)`, `UpdateCategory(categoryId, name?, type?, icon?)`, `ArchiveCategory`, `ErrorConsumed`) and `CategoryManagementUiState(income, expense, error?, operationFailed)` with an `Initial` companion.
    - Build `uiState` from `repository.observeCategories()`, partitioning on `type` into INCOME/EXPENSE groups; validate via `MetadataValidator` (TRANSFER rejected) before persisting; map `Invalid`/failure into state.
    - _Requirements: 7.2, 7.3, 8.1, 8.2, 8.4, 9.2, 9.3_

  - [x] 10.2 Implement `QuickPresetManagementViewModel`
    - Create `QuickPresetManagementEvent` (`CreatePreset(amount, label)`, `UpdatePreset(presetId, amount?, label?)`, `DeletePreset`, `ErrorConsumed`) and `QuickPresetManagementUiState(presets, error?, operationFailed)` with an `Initial` companion.
    - Build `uiState` from `repository.observeQuickPresets()`; validate via `MetadataValidator` before persisting; map `Invalid`/failure into state.
    - _Requirements: 11.2, 11.3, 12.1, 12.2, 12.4, 13.2, 13.3, 14.1_

  - [x] 10.3 Write unit tests for category and preset ViewModels
    - Assert TRANSFER type on create/update sets `state.error(TYPE_NOT_PERMITTED)` and does not persist (7.3, 9.3); invalid name/label/amount sets the corresponding error and does not persist; category state partitions active categories into income/expense groups (8.1).
    - _Requirements: 7.3, 8.1, 9.3, 11.3, 13.2_

- [x] 11. Checkpoint - ViewModels complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 12. Implement the wallet management Compose UI (Material 3)
  - [x] 12.1 Implement `WalletManagementScreen`
    - Stateless `WalletManagementScreen(state, onEvent)`: a `LazyColumn` of active wallets showing name, integer smallest-unit balance, and a default marker; per-row actions Set default / Archive / Edit name / Override balance. Numeric inputs (initial balance, override target) reuse Scope 1's `CustomNumpad` with a non-focusable amount display (no OS keyboard for numbers — UI Rule 1); primary actions/numpad in the lower region (UI Rule 2). Inline validation errors from `state.error`.
    - _Requirements: 2.1, 2.3, 1.1, 3.1, 4.1_

  - [x] 12.2 Write Compose UI tests for the wallet screen
    - Assert the wallet list renders name/balance/default marker; the balance and override inputs use the numpad with a non-focusable display (no OS keyboard for numbers); an inline error surfaces from `state.error`.
    - _Requirements: 2.1, 2.3, 4.1_

- [x] 13. Implement the category and preset management Compose UI (Material 3)
  - [x] 13.1 Implement `CategoryManagementScreen`
    - Stateless `CategoryManagementScreen(state, onEvent)`: two grouped sections INCOME and EXPENSE; create/edit flow with a name text field, an INCOME/EXPENSE single-choice control (TRANSFER never offered), and an optional icon picker; per-row Edit / Archive actions.
    - _Requirements: 8.1, 8.3, 7.1, 7.3, 7.4, 9.1, 9.3_

  - [x] 13.2 Implement `QuickPresetManagementScreen`
    - Stateless `QuickPresetManagementScreen(state, onEvent)`: a `LazyColumn` of presets showing amount and label; create/edit flow with a numpad-driven amount (`CustomNumpad`, non-focusable display) plus a label text field; per-row Edit / Delete (hard delete) actions in the lower region.
    - _Requirements: 12.1, 12.3, 11.1, 14.1_

  - [x] 13.3 Write Compose UI tests for the category and preset screens
    - Assert categories render in INCOME/EXPENSE groups and TRANSFER is never selectable (8.1, 7.3); the preset list renders amount/label and the amount input uses the numpad with a non-focusable display (12.1, 12.3); a Delete action dispatches `DeletePreset` (14.1).
    - _Requirements: 7.3, 8.1, 12.1, 12.3, 14.1_

- [x] 14. Wire the management screens into the app
  - [x] 14.1 Construct and connect the dependency graph
    - Provide `MetadataRepository` from the existing Room database / `DatabaseProvider`, construct the three ViewModels (factory or existing DI convention), collect each `uiState` in its screen, and add navigation/entry into the three management screens alongside the Scope 1 & 2 screens.
    - _Requirements: 2.1, 8.1, 12.1_

  - [x] 14.2 Write Room integration tests for the transaction-untouched boundary and reactive re-emission
    - Against a Room in-memory database with the wired repository: seed the `transactions` table, then run a `Direct_Balance_Override`, an archive, and a full CRUD cycle on each metadata entity, asserting the `transactions` row count and every field value are unchanged after each (headline Data Rule 3 / Requirement 15 check); assert `observeWallets()` re-emits the updated active set after create/update/archive/override, `observeCategories()` on category changes, and `observeQuickPresets()` on preset changes; assert an archived wallet/category is absent from the active `Flow` while still present in the table.
    - _Requirements: 15.1, 15.2, 15.3, 2.4, 5.2, 5.3, 8.4, 10.2, 10.3, 12.4_

- [x] 15. Final checkpoint - ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation sub-tasks are never optional.
- Each task references specific requirements for traceability.
- Property-based tests use `kotest-property`, one test per property, a minimum of 100 iterations, each tagged with `// Feature: customization-metadata, Property {number}: {property_text}`.
- Property 5 targets a pure function cross-checked against an independent reference selection; Properties 1, 2, 3, 4, 6, 7, 9, 10, 11 run against a Room in-memory database; Properties 8 and 12 target pure logic (`MetadataValidator` and `toDomain()` mappers).
- Reactive `Flow` re-emission, atomic-write rollback, the null-balance/null-icon mappings, and OS-keyboard suppression do not vary meaningfully with input and are covered by Room integration tests, Compose UI tests, and example unit tests rather than property tests.
- The feature adds no schema change or migration and reuses Scope 1 & 2 entities, domain models, and mappers; the transaction-untouched boundary (Data Rule 3 / Requirement 15) is enforced structurally by a `MetadataRepository` with no `TransactionDao` dependency.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1", "5.1", "6.1", "6.2"] },
    { "id": 1, "tasks": ["2.1", "3.1", "5.2"] },
    { "id": 2, "tasks": ["2.2", "3.2", "7.1"] },
    { "id": 3, "tasks": ["7.2", "7.3", "7.4", "7.5", "7.6", "7.7", "7.8", "7.9", "7.10", "7.11", "7.12"] },
    { "id": 4, "tasks": ["9.1", "10.1", "10.2"] },
    { "id": 5, "tasks": ["9.2", "10.3", "12.1", "13.1", "13.2"] },
    { "id": 6, "tasks": ["12.2", "13.3", "14.1"] },
    { "id": 7, "tasks": ["14.2"] }
  ]
}
```
