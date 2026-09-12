# Requirements Document

## Introduction

The Transaction Input Engine is the core data-entry feature of the Personal Finance Tracker Android application (Scope 1 of the PRD). Its purpose is to enable frictionless manual entry of financial transactions using a custom in-app numpad, accumulative quick-preset chips, and one-handed ergonomic layout, then persist each transaction and its balance effect atomically to a local offline-first store.

This feature covers three transaction types (Expense, Income, Transfer), input validation, and atomic local persistence. Every persisted transaction is written with a synchronization flag set to unsynced so that a separate synchronization scope can later transmit the transaction to the backend. Backend synchronization itself is out of scope for this feature.

## Glossary

- **Input_Engine**: The application subsystem responsible for capturing transaction input, validating it, and persisting it locally. Encompasses the input UI and its supporting local persistence logic.
- **Numpad**: The custom in-app numeric keypad Composable used to enter transaction amounts. Referred to in the PRD as the "Custom Numpad Composable".
- **Preset_Chip**: A tappable UI element representing a fixed monetary value (for example, +10.000 or +50.000) that adds its value to the running amount when tapped.
- **Running_Amount**: The current numeric amount being entered for the transaction, expressed as a non-negative integer of the smallest currency unit.
- **Transaction**: A financial record with the fields id, timestamp, type, amount, source_wallet, dest_wallet, category, and note.
- **Transaction_Type**: One of the enumerated values EXPENSE, INCOME, or TRANSFER.
- **Wallet**: A named store of monetary value ("kantong") with an associated balance.
- **Default_Wallet**: The Wallet flagged as the default source wallet for new transactions.
- **Category**: A labeled classification assigned to a Transaction (for example, "Makan").
- **Note**: An optional free-text description attached to a Transaction.
- **Submit_Control**: The UI control that, when activated, triggers persistence of the current Transaction.
- **Transactions_Store**: The local Room database table named `transactions` that persists Transaction records.
- **Wallets_Store**: The local Room database table named `wallets` that persists Wallet records and balances.
- **Sync_Flag**: The `is_synced` field of a Transaction record indicating whether the record has been transmitted to the backend. A value of 0 means unsynced.
- **Atomic_Write**: A single database transaction (`@Transaction`) that persists a Transaction record and applies the corresponding Wallet balance changes together, such that either all changes are committed or none are.

## Requirements

### Requirement 1: Enter Transaction Amount via Custom Numpad

**User Story:** As a user, I want to enter transaction amounts using an in-app numpad, so that I can input numbers quickly without the operating system keyboard appearing.

#### Acceptance Criteria

1. WHEN the transaction input screen is displayed, THE Input_Engine SHALL present the Numpad for amount entry without requesting the operating system keyboard.
2. WHEN a digit key on the Numpad is tapped, THE Input_Engine SHALL append the corresponding digit to the Running_Amount.
3. WHEN the delete key on the Numpad is tapped, THE Input_Engine SHALL remove the least significant digit from the Running_Amount.
4. WHEN the Running_Amount is zero and the delete key on the Numpad is tapped, THE Input_Engine SHALL keep the Running_Amount at zero.
5. THE Input_Engine SHALL display the Running_Amount using integer values of the smallest currency unit.

### Requirement 2: Add Amounts via Accumulative Preset Chips

**User Story:** As a user, I want quick preset chips that add fixed amounts to the running total, so that I can compose common amounts with fewer taps.

#### Acceptance Criteria

1. WHEN the transaction input screen is displayed, THE Input_Engine SHALL present the configured Preset_Chip set.
2. WHEN a Preset_Chip is tapped, THE Input_Engine SHALL add the value of the tapped Preset_Chip to the Running_Amount.
3. WHEN multiple Preset_Chip taps occur, THE Input_Engine SHALL accumulate each tapped value into the Running_Amount.
4. WHEN a Preset_Chip value is added to the Running_Amount, THE Input_Engine SHALL display the updated Running_Amount.

### Requirement 3: Select Transaction Type

**User Story:** As a user, I want to choose the transaction type, so that the entry is recorded as an expense, income, or transfer.

#### Acceptance Criteria

1. THE Input_Engine SHALL allow selection of exactly one Transaction_Type from EXPENSE, INCOME, and TRANSFER.
2. WHEN the transaction input screen is first displayed, THE Input_Engine SHALL set the selected Transaction_Type to a defined default value.
3. WHEN a Transaction_Type is selected, THE Input_Engine SHALL display the selected Transaction_Type as active.

### Requirement 4: Auto-Select Default Wallet

**User Story:** As a user, I want the default wallet pre-selected, so that I can record a transaction without manually choosing a source wallet each time.

#### Acceptance Criteria

1. WHEN the transaction input screen is displayed, THE Input_Engine SHALL set the source wallet to the Default_Wallet.
2. THE Input_Engine SHALL allow the user to change the selected source wallet to any non-archived Wallet.
3. WHERE the selected Transaction_Type is TRANSFER, THE Input_Engine SHALL allow selection of a destination wallet that is a non-archived Wallet different from the source wallet.
4. IF the selected Transaction_Type is TRANSFER and the destination wallet equals the source wallet, THEN THE Input_Engine SHALL keep the Submit_Control disabled.

### Requirement 5: Select Category

**User Story:** As a user, I want to assign a category to a transaction, so that my spending and income are classified.

#### Acceptance Criteria

1. THE Input_Engine SHALL allow selection of exactly one Category for the Transaction.
2. WHEN the selected Transaction_Type is EXPENSE, THE Input_Engine SHALL present Categories of the expense type for selection.
3. WHEN the selected Transaction_Type is INCOME, THE Input_Engine SHALL present Categories of the income type for selection.
4. WHEN a Category is selected, THE Input_Engine SHALL display the selected Category as active.

### Requirement 6: Enter Optional Note

**User Story:** As a user, I want to optionally add a note to a transaction, so that I can record additional context when needed.

#### Acceptance Criteria

1. THE Input_Engine SHALL provide a Note field that accepts free text.
2. WHILE the Note field has not been tapped, THE Input_Engine SHALL keep the operating system keyboard hidden.
3. WHEN the Note field is tapped, THE Input_Engine SHALL display the operating system keyboard for Note entry.
4. WHEN the Submit_Control is activated and the Note field is empty, THE Input_Engine SHALL persist the Transaction with an empty Note.

### Requirement 7: Validate Input Before Submission

**User Story:** As a user, I want the submit control enabled only when the entry is valid, so that I cannot save an incomplete transaction.

#### Acceptance Criteria

1. WHILE the Running_Amount is greater than zero and a Category is selected, THE Input_Engine SHALL enable the Submit_Control.
2. IF the Running_Amount is zero, THEN THE Input_Engine SHALL keep the Submit_Control disabled.
3. IF no Category is selected, THEN THE Input_Engine SHALL keep the Submit_Control disabled.

### Requirement 8: One-Handed Ergonomic Layout

**User Story:** As a user, I want the main interaction controls within reach of my thumb, so that I can operate the app with one hand.

#### Acceptance Criteria

1. THE Input_Engine SHALL position the Numpad in the lower region of the screen.
2. THE Input_Engine SHALL position the Submit_Control in the lower region of the screen.

### Requirement 9: Persist Transaction Atomically with Balance Update

**User Story:** As a user, I want each saved transaction to update wallet balances reliably, so that my recorded balances stay accurate.

#### Acceptance Criteria

1. WHEN the Submit_Control is activated with a valid entry, THE Input_Engine SHALL persist the Transaction to the Transactions_Store and apply the corresponding Wallet balance changes within a single Atomic_Write.
2. WHEN a Transaction of type EXPENSE is persisted, THE Input_Engine SHALL decrease the source Wallet balance by the transaction amount within the Atomic_Write.
3. WHEN a Transaction of type INCOME is persisted, THE Input_Engine SHALL increase the source Wallet balance by the transaction amount within the Atomic_Write.
4. WHEN a Transaction of type TRANSFER is persisted, THE Input_Engine SHALL decrease the source Wallet balance and increase the destination Wallet balance by the transaction amount within the Atomic_Write.
5. IF the Atomic_Write fails, THEN THE Input_Engine SHALL leave the Transactions_Store and Wallets_Store unchanged and SHALL return an error indication.

### Requirement 10: Assign Transaction Identity and Metadata

**User Story:** As a user, I want each transaction stored with a unique identity and timestamp, so that it can be later synchronized without duplication.

#### Acceptance Criteria

1. WHEN a Transaction is persisted, THE Input_Engine SHALL assign a UUID string as the Transaction id.
2. WHEN a Transaction is persisted, THE Input_Engine SHALL assign the current time as an epoch-millisecond timestamp to the Transaction.
3. WHEN a Transaction is persisted, THE Input_Engine SHALL record the selected Transaction_Type, the Running_Amount as the amount, the source wallet, the Category, and the Note on the Transaction record.
4. WHEN a Transaction of type TRANSFER is persisted, THE Input_Engine SHALL record the destination wallet on the Transaction record.
5. WHEN a Transaction of type EXPENSE or INCOME is persisted, THE Input_Engine SHALL record a null destination wallet on the Transaction record.

### Requirement 11: Mark Persisted Transactions as Unsynced

**User Story:** As a user, I want newly saved transactions marked as pending sync, so that they can be transmitted to the backend later.

#### Acceptance Criteria

1. WHEN a Transaction is persisted, THE Input_Engine SHALL set the Sync_Flag of the Transaction record to 0.

### Requirement 12: Real-Time UI Observation of Data Changes

**User Story:** As a user, I want the interface to reflect balance and transaction changes immediately, so that I see accurate state without manual refresh.

#### Acceptance Criteria

1. THE Input_Engine SHALL expose Wallet balances to the UI through an observable stream.
2. WHEN a Wallet balance changes in the Wallets_Store, THE Input_Engine SHALL emit the updated Wallet balance through the observable stream.
3. WHEN a Transaction is persisted, THE Input_Engine SHALL reset the Running_Amount to zero and the selected Category to none for the next entry.
