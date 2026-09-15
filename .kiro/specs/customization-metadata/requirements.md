# Requirements Document

## Introduction

The Customization & Metadata feature is Scope 3 of the Personal Finance Tracker Android application. Its purpose is to let the user manage the metadata entities that the Transaction Input Engine (Scope 1) and the Transaction History & Audit feature (Scope 2) consume: Wallets ("kantong"), Categories, and Quick Presets. The feature provides create, read, update, and archive operations for Wallets and Categories, create/read/update/delete operations for Quick Presets, assignment of a single default Wallet, and a Direct Balance Override that lets the user manually set a Wallet balance.

A defining constraint of this feature is that a Direct Balance Override changes only the balance of the target Wallet and never records a Transaction. This distinguishes an override from an ordinary transaction, which changes a Wallet balance by way of a persisted log entry. Wallets and Categories are removed by archiving (soft delete) rather than by hard delete, so that historical Transaction records that reference them remain valid and consistent with the "non-archived" selection behavior in Scope 1 and Scope 2.

Terminology, the `TransactionType` classification (INCOME and EXPENSE for Categories; TRANSFER carries no Category), and the representation of balances and amounts as non-negative `Long` integers in the smallest currency unit are kept consistent with the Transaction Input Engine (Scope 1). Because data is observed through reactive streams, when the user creates, updates, archives, deletes, or overrides a metadata entity, the change is reflected automatically in Scope 1 (input selectors and preset chips) and Scope 2 (history and filter chips) without a manual refresh.

This feature does not record transactions (Scope 1), does not display transaction history (Scope 2), and does not perform backend synchronization (Scope 4). Its responsibility is limited to CRUD, archive, and override of local metadata.

## Glossary

- **Metadata_Module**: The application subsystem responsible for creating, reading, updating, archiving, deleting, and overriding Wallet, Category, and Quick_Preset records, together with its supporting persistence logic.
- **Wallet**: A named store of monetary value ("kantong") with the fields id, name, balance, is_default, and is_archived.
- **Category**: A labeled classification with the fields id, name, type, icon, and is_archived, used to classify Transaction records.
- **Quick_Preset**: A configurable numeric preset with the fields id, amount, and label, used to populate the accumulative preset chips of the Transaction Input Engine.
- **Transaction_Type**: One of the enumerated values EXPENSE, INCOME, or TRANSFER, consistent with the Transaction Input Engine. A Category is classified as INCOME or EXPENSE only.
- **Wallets_Store**: The local Room database table named `wallets` that persists Wallet records.
- **Categories_Store**: The local Room database table named `categories` that persists Category records.
- **Presets_Store**: The local Room database table named `quick_presets` that persists Quick_Preset records.
- **Transactions_Store**: The local Room database table named `transactions` that persists Transaction records, as defined by the Transaction Input Engine.
- **Balance**: The monetary value held by a Wallet, expressed as a `Long` integer of the smallest currency unit.
- **Amount**: The numeric value of a Quick_Preset, expressed as a `Long` integer of the smallest currency unit.
- **Default_Wallet**: The single Wallet whose is_default value is true, used by the Transaction Input Engine as the pre-selected source Wallet.
- **Direct_Balance_Override**: An operation that sets a Wallet Balance to a user-specified value by modifying only the Wallet record, without creating any Transaction record.
- **Archive**: A soft-delete operation that sets the is_archived value of a Wallet or Category to true, retaining the record so historical references stay valid.
- **Active_Wallet**: A Wallet whose is_archived value is false.
- **Active_Category**: A Category whose is_archived value is false.
- **Observable_Stream**: A reactive stream (Flow/StateFlow) through which the Metadata_Module exposes record collections to the UI for real-time observation.

## Requirements

### Requirement 1: Create a Wallet

**User Story:** As a user, I want to create a wallet, so that I can track balances across separate kantong.

#### Acceptance Criteria

1. WHEN the user submits a Wallet whose name, after leading and trailing whitespace is trimmed, contains 1 to 100 characters inclusive and whose initial Balance is an integer in the smallest currency unit from 0 to 999999999999 inclusive, THE Metadata_Module SHALL persist a new Wallet record in the Wallets_Store with the trimmed name, the submitted Balance, is_default equal to false, and is_archived equal to false.
2. IF the user submits a Wallet whose name, after leading and trailing whitespace is trimmed, is empty or exceeds 100 characters, THEN THE Metadata_Module SHALL reject the creation, SHALL retain the Wallets_Store unchanged, and SHALL return an indication identifying the invalid name.
3. IF the user submits a Wallet whose specified initial Balance is negative or exceeds 999999999999 in the smallest currency unit, THEN THE Metadata_Module SHALL reject the creation, SHALL retain the Wallets_Store unchanged, and SHALL return an indication identifying the invalid Balance.
4. WHEN the user submits a valid Wallet without specifying an initial Balance, THE Metadata_Module SHALL persist the new Wallet record with a Balance of zero.
5. WHERE the Wallets_Store contains no Active_Wallet, WHEN a Wallet is created, THE Metadata_Module SHALL set the created Wallet as the Default_Wallet with is_default equal to true; otherwise THE Metadata_Module SHALL set is_default equal to false.

### Requirement 2: Read Wallets

**User Story:** As a user, I want to view my wallets, so that I can manage them and see their balances.

#### Acceptance Criteria

1. THE Metadata_Module SHALL expose the collection of Active_Wallet records to the UI through an Observable_Stream.
2. WHEN a subscriber begins observing the Wallet Observable_Stream, THE Metadata_Module SHALL emit the current collection of Active_Wallet records to that subscriber.
3. THE Metadata_Module SHALL include for each emitted Wallet record the name, the Balance, the is_default value, and the is_archived value.
4. WHEN a Wallet record in the Wallets_Store is created, updated, archived, or overridden, THE Metadata_Module SHALL emit an updated Wallet collection reflecting the change through the Observable_Stream.

### Requirement 3: Update a Wallet

**User Story:** As a user, I want to edit a wallet's name, so that I can correct or rename it.

#### Acceptance Criteria

1. WHEN the user submits an updated name for an existing Wallet that, after leading and trailing whitespace is trimmed, contains 1 to 100 characters inclusive, THE Metadata_Module SHALL persist the trimmed name on that Wallet record in the Wallets_Store.
2. IF the user submits an updated name that, after trimming, is empty or exceeds 100 characters for an existing Wallet, THEN THE Metadata_Module SHALL reject the update, SHALL retain the existing Wallet name unchanged, and SHALL return an indication identifying the invalid name.
3. WHEN the user updates a Wallet name, THE Metadata_Module SHALL leave the Balance, the is_default value, and the is_archived value of that Wallet unchanged.

### Requirement 4: Direct Balance Override

**User Story:** As a user, I want to manually set a wallet's balance, so that I can correct it to match reality without logging a fake transaction.

#### Acceptance Criteria

1. WHEN the user submits a Direct_Balance_Override for a Wallet with a target Balance that is an integer in the smallest currency unit from 0 to 999999999999 inclusive, THE Metadata_Module SHALL set that Wallet Balance to the submitted target value in the Wallets_Store within a single atomic write.
2. IF the user submits a Direct_Balance_Override whose target Balance is negative or exceeds 999999999999 in the smallest currency unit, THEN THE Metadata_Module SHALL reject the override, SHALL retain the Wallets_Store unchanged, and SHALL return an indication identifying the invalid Balance.
3. WHEN a Direct_Balance_Override is applied, THE Metadata_Module SHALL create no Transaction record in the Transactions_Store.
4. WHEN a Direct_Balance_Override is applied, THE Metadata_Module SHALL leave the count of records in the Transactions_Store unchanged.
5. WHEN a Direct_Balance_Override is applied to a Wallet, THE Metadata_Module SHALL leave the name, the is_default value, and the is_archived value of that Wallet unchanged.
6. WHEN a Direct_Balance_Override is applied, THE Metadata_Module SHALL leave the Balance of every other Wallet in the Wallets_Store unchanged.
7. IF the atomic write for a Direct_Balance_Override fails, THEN THE Metadata_Module SHALL retain the target Wallet Balance at its value prior to the override and SHALL return an error indication.

### Requirement 5: Archive a Wallet

**User Story:** As a user, I want to archive a wallet I no longer use, so that it stops appearing in selectors while its historical transactions stay valid.

#### Acceptance Criteria

1. WHEN the user archives a Wallet whose is_archived value is false, THE Metadata_Module SHALL set the is_archived value of that Wallet record to true in the Wallets_Store.
2. WHEN a Wallet is archived, THE Metadata_Module SHALL retain the archived Wallet record in the Wallets_Store rather than deleting the record.
3. WHEN a Wallet is archived, THE Metadata_Module SHALL exclude that Wallet from the collection of Active_Wallet records emitted through the Observable_Stream.
4. WHEN a Wallet is archived, THE Metadata_Module SHALL leave every Transaction record that references that Wallet unchanged in the Transactions_Store.
5. IF the user archives the Default_Wallet AND at least one other Active_Wallet exists, THEN THE Metadata_Module SHALL assign the Default_Wallet designation to the remaining Active_Wallet whose name sorts first in case-insensitive ascending order, resolving ties by the lowest wallet identifier.
6. IF the user archives the Default_Wallet AND no other Active_Wallet exists, THEN THE Metadata_Module SHALL leave no Wallet designated as the Default_Wallet.
7. IF the user archives a Wallet whose is_archived value is already true, THEN THE Metadata_Module SHALL leave the Wallets_Store, the Default_Wallet designation, and the Transactions_Store unchanged.
8. IF the user attempts to archive a Wallet that does not exist in the Wallets_Store, THEN THE Metadata_Module SHALL reject the operation, SHALL leave the Wallets_Store unchanged, and SHALL return an error indication identifying that the Wallet was not found.

### Requirement 6: Assign the Default Wallet

**User Story:** As a user, I want to set a default wallet, so that new transactions start with my most-used kantong pre-selected.

#### Acceptance Criteria

1. WHEN the user designates an Active_Wallet as the Default_Wallet, THE Metadata_Module SHALL, within a single atomic operation, set the is_default value of that Wallet record to true and set the is_default value of every other Wallet record to false, such that the operation either completes fully or leaves all Wallet records unchanged.
2. THE Metadata_Module SHALL maintain the invariant that exactly one Wallet record has an is_default value of true whenever at least one Active_Wallet exists in the Wallets_Store, and SHALL never expose an intermediate state with zero or two or more Wallet records whose is_default value is true.
3. WHEN the user designates an Active_Wallet that is already the Default_Wallet, THE Metadata_Module SHALL treat the designation as a no-op and SHALL leave all Wallet records unchanged.
4. IF the user attempts to designate an archived Wallet as the Default_Wallet, THEN THE Metadata_Module SHALL reject the designation, SHALL retain the existing Default_Wallet designation unchanged, and SHALL return an indication that the designation failed because the target Wallet is archived.
5. IF the atomic designation operation fails before completion, THEN THE Metadata_Module SHALL roll back all changes so that the prior Default_Wallet designation is retained, and SHALL return an indication that the designation failed.

### Requirement 7: Create a Category

**User Story:** As a user, I want to create a category, so that I can classify my income and expenses.

#### Acceptance Criteria

1. WHEN the user submits a Category whose name, after leading and trailing whitespace is trimmed, contains 1 to 50 characters inclusive, whose Transaction_Type is INCOME or EXPENSE, and with an optional icon, THE Metadata_Module SHALL persist a new Category record in the Categories_Store with the trimmed name, the submitted Transaction_Type, the submitted icon, and is_archived equal to false.
2. IF the user submits a Category whose name, after trimming, is empty or exceeds 50 characters, THEN THE Metadata_Module SHALL reject the creation, SHALL retain the Categories_Store unchanged, and SHALL return an error indication that the name is invalid.
3. IF the user submits a Category whose Transaction_Type is TRANSFER, THEN THE Metadata_Module SHALL reject the creation, SHALL retain the Categories_Store unchanged, and SHALL return an error indication that the Transaction_Type is not permitted.
4. WHEN the user submits a valid Category without an icon, THE Metadata_Module SHALL persist the new Category record with a null icon.

### Requirement 8: Read Categories

**User Story:** As a user, I want to view my categories grouped by type, so that I can manage income and expense classifications.

#### Acceptance Criteria

1. THE Metadata_Module SHALL expose the collection of Active_Category records to the UI through an Observable_Stream.
2. WHEN a subscriber begins observing the Category Observable_Stream, THE Metadata_Module SHALL emit the current collection of Active_Category records to that subscriber.
3. THE Metadata_Module SHALL include for each emitted Category record the name, the Transaction_Type, the icon, and the is_archived value.
4. WHEN a Category record in the Categories_Store is created, updated, or archived, THE Metadata_Module SHALL emit an updated Category collection reflecting the change through the Observable_Stream.

### Requirement 9: Update a Category

**User Story:** As a user, I want to edit a category's name, icon, and type, so that I can keep my classifications accurate.

#### Acceptance Criteria

1. WHEN the user submits an update for an existing Category that includes a non-empty name of 1 to 50 characters, an updated icon, or an updated Transaction_Type of INCOME or EXPENSE, THE Metadata_Module SHALL persist the submitted values on that Category record in the Categories_Store and SHALL leave any field not included in the submission unchanged.
2. IF the user submits an updated name that is empty, contains only whitespace, or exceeds 50 characters for an existing Category, THEN THE Metadata_Module SHALL reject the update, SHALL retain all existing Category values unchanged, and SHALL return an error indication that the name is invalid.
3. IF the user submits an updated Transaction_Type of TRANSFER for an existing Category, THEN THE Metadata_Module SHALL reject the update, SHALL retain the existing Category Transaction_Type unchanged, and SHALL return an error indication that the Transaction_Type is not permitted.
4. WHEN the user updates a Category, THE Metadata_Module SHALL leave the is_archived value of that Category unchanged.
5. WHEN the user changes the Transaction_Type of an existing Category from EXPENSE to INCOME or from INCOME to EXPENSE, THE Metadata_Module SHALL leave every existing Transaction record in the Transactions_Store that references that Category unchanged.

### Requirement 10: Archive a Category

**User Story:** As a user, I want to archive a category I no longer use, so that it stops appearing in selectors while its historical transactions stay valid.

#### Acceptance Criteria

1. WHEN the user archives a Category whose is_archived value is false, THE Metadata_Module SHALL set the is_archived value of that Category record to true in the Categories_Store.
2. WHEN a Category is archived, THE Metadata_Module SHALL retain the archived Category record in the Categories_Store rather than deleting the record.
3. WHEN a Category is archived, THE Metadata_Module SHALL exclude that Category from the collection of Active_Category records emitted through the Observable_Stream.
4. WHEN a Category is archived, THE Metadata_Module SHALL leave every Transaction record that references that Category unchanged in the Transactions_Store.
5. IF the user archives a Category whose is_archived value is already true, THEN THE Metadata_Module SHALL leave the Categories_Store and the Transactions_Store unchanged.

### Requirement 11: Create a Quick Preset

**User Story:** As a user, I want to create numeric presets, so that I can enter common amounts with a single tap.

#### Acceptance Criteria

1. WHEN the user submits a Quick_Preset whose Amount is an integer in the smallest currency unit from 1 to 999999999999 inclusive and whose label, after leading and trailing whitespace is trimmed, contains 1 to 50 characters inclusive, THE Metadata_Module SHALL persist a new Quick_Preset record in the Presets_Store with the submitted Amount and the trimmed label.
2. IF the user submits a Quick_Preset whose Amount is zero, negative, or exceeds 999999999999 in the smallest currency unit, THEN THE Metadata_Module SHALL reject the creation, SHALL retain the Presets_Store unchanged, and SHALL return an indication identifying the invalid Amount.
3. IF the user submits a Quick_Preset whose label, after trimming, is empty or exceeds 50 characters, THEN THE Metadata_Module SHALL reject the creation, SHALL retain the Presets_Store unchanged, and SHALL return an indication identifying the invalid label.

### Requirement 12: Read Quick Presets

**User Story:** As a user, I want to view my presets, so that I can manage the values available on the numpad.

#### Acceptance Criteria

1. THE Metadata_Module SHALL expose the collection of Quick_Preset records to the UI through an Observable_Stream.
2. WHEN a subscriber begins observing the Quick_Preset Observable_Stream, THE Metadata_Module SHALL emit the current collection of Quick_Preset records to that subscriber.
3. THE Metadata_Module SHALL include for each emitted Quick_Preset record the Amount and the label.
4. WHEN a Quick_Preset record in the Presets_Store is created, updated, or deleted, THE Metadata_Module SHALL emit an updated Quick_Preset collection reflecting the change through the Observable_Stream.

### Requirement 13: Update a Quick Preset

**User Story:** As a user, I want to edit a preset's value and label, so that I can adjust the amounts I use most.

#### Acceptance Criteria

1. WHEN the user submits for an existing Quick_Preset an updated Amount that is an integer in the smallest currency unit from 1 to 999999999999 inclusive, or an updated label of 1 to 50 characters after trimming, THE Metadata_Module SHALL persist the submitted values on that Quick_Preset record in the Presets_Store and SHALL leave any field not included in the submission unchanged.
2. IF the user submits an updated Amount that is zero, negative, or exceeds 999999999999 in the smallest currency unit for an existing Quick_Preset, THEN THE Metadata_Module SHALL reject the update, SHALL retain the existing Quick_Preset values unchanged, and SHALL return an indication identifying the invalid Amount.
3. IF the user submits an updated label that, after trimming, is empty or exceeds 50 characters for an existing Quick_Preset, THEN THE Metadata_Module SHALL reject the update, SHALL retain the existing Quick_Preset values unchanged, and SHALL return an indication identifying the invalid label.

### Requirement 14: Delete a Quick Preset

**User Story:** As a user, I want to delete a preset, so that I can remove values I no longer need.

#### Acceptance Criteria

1. WHEN the user deletes a Quick_Preset, THE Metadata_Module SHALL remove that Quick_Preset record from the Presets_Store.
2. WHEN a Quick_Preset is deleted, THE Metadata_Module SHALL leave every Transaction record in the Transactions_Store unchanged.
3. WHEN a Quick_Preset is deleted, THE Metadata_Module SHALL leave every other Quick_Preset record in the Presets_Store unchanged.

### Requirement 15: Preserve Transaction Integrity Under Metadata Changes

**User Story:** As a user, I want managing metadata to never alter my recorded transactions, so that my financial history stays trustworthy.

#### Acceptance Criteria

1. WHEN the user performs any create, update, archive, delete, or Direct_Balance_Override operation on a Wallet, Category, or Quick_Preset, THE Metadata_Module SHALL create no Transaction record in the Transactions_Store.
2. WHEN the user performs any create, update, archive, delete, or Direct_Balance_Override operation on a Wallet, Category, or Quick_Preset, THE Metadata_Module SHALL leave the count of records in the Transactions_Store unchanged.
3. WHEN the user performs any create, update, archive, delete, or Direct_Balance_Override operation on a Wallet, Category, or Quick_Preset, THE Metadata_Module SHALL leave the field values of every existing Transaction record in the Transactions_Store unchanged.
4. IF a persistence operation on a Wallet, Category, or Quick_Preset fails, THEN THE Metadata_Module SHALL surface an error indication and SHALL retain the affected store in its state prior to the operation.
