# Requirements Document

## Introduction

The Transaction History & Audit feature is Scope 2 of the Personal Finance Tracker Android application. Its purpose is to let the user review, filter, sort, and aggregate the financial transactions that were previously recorded by the Transaction Input Engine (Scope 1). The feature reads transactions from the local offline-first store and presents them as a scrollable list, with a header of horizontal filter chips, sort controls, a dynamic summary card that aggregates the currently filtered transactions, and a per-row indicator of each transaction's synchronization status.

This feature is strictly read-only with respect to transactions: it displays, filters, sorts, and aggregates existing records. It does not create, edit, override, or delete transactions, and it does not transmit data to the backend. Transaction creation is handled by Scope 1 and backend synchronization is handled by a separate scope; this feature only reads the existing `is_synced` value to render a status indicator.

Terminology, the `TransactionType` enumeration, and the representation of amounts as non-negative `Long` integers in the smallest currency unit are kept consistent with the Transaction Input Engine (Scope 1). Because data is observed through reactive streams, when the Transaction Input Engine persists a new transaction the list and the summary card update automatically without a manual refresh.

## Glossary

- **History_Module**: The application subsystem responsible for reading, filtering, sorting, aggregating, and displaying recorded transactions. Encompasses the history UI and its supporting read-only query and aggregation logic.
- **Transaction**: A financial record with the fields id, timestamp, type, amount, source_wallet, dest_wallet, category, note, and is_synced, as recorded by the Transaction Input Engine.
- **Transaction_Type**: One of the enumerated values EXPENSE, INCOME, or TRANSFER, consistent with the Transaction Input Engine.
- **Transactions_Store**: The local Room database table named `transactions` that persists Transaction records.
- **Wallet**: A named store of monetary value ("kantong") with an associated balance, identified by a wallet id.
- **Category**: A labeled classification assigned to a Transaction, identified by a category id.
- **Sync_Status**: The synchronization state of a Transaction derived from its `is_synced` field, where a value of 1 is SYNCED and a value of 0 is PENDING.
- **Transaction_List**: The scrollable list of Transaction records presented to the user.
- **Transaction_Row**: A single entry in the Transaction_List representing one Transaction.
- **Filter_Set**: The collection of currently active filter selections across the filter dimensions Time, Transaction_Type, Wallet, Category, and Sync_Status.
- **Filter_Chip**: A tappable UI element rendered in a horizontal header row that activates or deactivates a filter selection.
- **Time_Filter**: A filter dimension that restricts the Transaction_List to transactions whose timestamp falls within a selected time range.
- **Sort_Order**: The ordering applied to the Transaction_List, one of Time-Newest-First, Time-Oldest-First, Amount-Largest-First, or Amount-Smallest-First.
- **Summary_Card**: The UI element that displays aggregate totals computed over the currently filtered Transaction_List.
- **Filtered_Set**: The set of Transaction records that satisfy the currently active Filter_Set.
- **Amount**: The monetary value of a Transaction, expressed as a non-negative integer of the smallest currency unit.
- **Sync_Indicator**: The per-row visual element that represents the Sync_Status of a Transaction.

## Requirements

### Requirement 1: Display Transaction List

**User Story:** As a user, I want to see a list of my recorded transactions, so that I can review my financial history.

#### Acceptance Criteria

1. WHEN the history screen is displayed, THE History_Module SHALL present the Transaction_List derived from the Filtered_Set of Transaction records read from the Transactions_Store.
2. THE History_Module SHALL display on each Transaction_Row the Transaction_Type, the Amount, the source Wallet, the Category, and the timestamp of the Transaction as an observable date-and-time value.
3. WHEN a Transaction of type TRANSFER is displayed, THE History_Module SHALL display the destination Wallet on the Transaction_Row.
4. THE History_Module SHALL display each Amount using integer values of the smallest currency unit.
5. WHILE no Sort_Order has been changed by the user, THE History_Module SHALL order the Transaction_List by timestamp in descending order.
6. IF the Filtered_Set contains no Transaction records, THEN THE History_Module SHALL display an empty-state indication in place of the Transaction_List.
7. IF reading Transaction records from the Transactions_Store fails, THEN THE History_Module SHALL display an error indication and SHALL preserve the previously displayed Transaction_List state.

### Requirement 2: Real-Time Observation of Transaction Changes

**User Story:** As a user, I want the history to update automatically when new transactions are recorded, so that I always see current data without refreshing.

#### Acceptance Criteria

1. THE History_Module SHALL expose the Transaction_List to the UI through an observable stream.
2. WHEN a subscriber begins observing the Transaction_List stream, THE History_Module SHALL emit the current Transaction_List subject to the active Filter_Set to that subscriber.
3. WHEN a Transaction record is added to the Transactions_Store, THE History_Module SHALL emit an updated Transaction_List that reflects the added Transaction subject to the active Filter_Set.
4. WHEN a Transaction record in the Transactions_Store changes, THE History_Module SHALL emit an updated Transaction_List that reflects the change subject to the active Filter_Set.
5. WHEN a Transaction record is removed from the Transactions_Store, THE History_Module SHALL emit an updated Transaction_List that excludes the removed Transaction subject to the active Filter_Set.

### Requirement 3: Filter by Transaction Type

**User Story:** As a user, I want to filter transactions by type, so that I can view only expenses, only income, or only transfers.

#### Acceptance Criteria

1. THE History_Module SHALL present exactly one Filter_Chip for each Transaction_Type value EXPENSE, INCOME, and TRANSFER, for a total of three Filter_Chips.
2. WHILE no Transaction_Type Filter_Chip is activated, THE History_Module SHALL display all Transaction records in the Transaction_List regardless of Transaction_Type.
3. WHEN a Transaction_Type Filter_Chip is activated, THE History_Module SHALL restrict the Transaction_List to Transaction records whose Transaction_Type matches any currently activated Filter_Chip selection, combining multiple activated selections with OR.
4. WHEN a Transaction_Type Filter_Chip is deactivated, THE History_Module SHALL remove the Transaction_Type restriction contributed by that selection and re-evaluate the Transaction_List against the remaining activated Filter_Chip selections.
5. IF the set of activated Transaction_Type Filter_Chip selections yields zero matching Transaction records, THEN THE History_Module SHALL display an empty-state indication communicating that no Transaction records match the active filter while retaining the activated Filter_Chip selections.

### Requirement 4: Filter by Wallet

**User Story:** As a user, I want to filter transactions by wallet, so that I can review the activity of a specific kantong.

#### Acceptance Criteria

1. THE History_Module SHALL present exactly one Filter_Chip for each Wallet available for filtering.
2. WHEN a Wallet Filter_Chip is activated, THE History_Module SHALL restrict the Transaction_List to Transaction records whose source Wallet matches the activated Wallet OR whose destination Wallet matches the activated Wallet.
3. WHEN a Wallet Filter_Chip is deactivated, THE History_Module SHALL remove the Wallet restriction contributed by that Filter_Chip and retain the restrictions contributed by any Filter_Chip that remains activated.
4. WHILE two or more Wallet Filter_Chips are activated, THE History_Module SHALL include in the Transaction_List every Transaction record whose source Wallet or destination Wallet matches any one of the activated Wallets.
5. WHILE no Wallet Filter_Chip is activated, THE History_Module SHALL present all Transaction records in the Transaction_List without any Wallet restriction.
6. IF no Transaction record matches the activated Wallet Filter_Chip selection, THEN THE History_Module SHALL present an empty Transaction_List together with an indication that no transactions match the current filter.

### Requirement 5: Filter by Category

**User Story:** As a user, I want to filter transactions by category, so that I can review spending or income in a specific classification.

#### Acceptance Criteria

1. THE History_Module SHALL present exactly one Filter_Chip for each Category available for filtering.
2. WHILE no Category Filter_Chip is activated, THE History_Module SHALL display all Transaction records in the Transaction_List regardless of Category.
3. WHEN a Category Filter_Chip is activated, THE History_Module SHALL restrict the Transaction_List to Transaction records whose Category matches any currently activated Category Filter_Chip selection, combining multiple activated selections with OR.
4. WHEN a Category Filter_Chip is deactivated, THE History_Module SHALL remove the Category restriction contributed by that selection and re-evaluate the Transaction_List against the remaining activated Category Filter_Chip selections.
5. IF the set of activated Category Filter_Chip selections yields zero matching Transaction records, THEN THE History_Module SHALL display an empty-state indication communicating that no Transaction records match the active filter while retaining the activated Filter_Chip selections.

### Requirement 6: Filter by Synchronization Status

**User Story:** As a user, I want to filter transactions by sync status, so that I can see which transactions are still pending upload.

#### Acceptance Criteria

1. THE History_Module SHALL present a Filter_Chip for the Sync_Status value SYNCED and a Filter_Chip for the Sync_Status value PENDING.
2. WHEN the History_Module is first displayed, THE History_Module SHALL render both Sync_Status Filter_Chips in the inactive state and present the Transaction_List without any Sync_Status restriction.
3. WHEN the SYNCED Filter_Chip is activated, THE History_Module SHALL restrict the Transaction_List to Transaction records whose `is_synced` value is 1.
4. WHEN the PENDING Filter_Chip is activated, THE History_Module SHALL restrict the Transaction_List to Transaction records whose `is_synced` value is 0.
5. WHILE both the SYNCED Filter_Chip and the PENDING Filter_Chip are active, THE History_Module SHALL present the Transaction_List containing Transaction records whose `is_synced` value is 0 or 1.
6. WHEN a Sync_Status Filter_Chip is deactivated, THE History_Module SHALL remove the Sync_Status restriction contributed by that selection from the Transaction_List.
7. IF an active Sync_Status Filter_Chip selection yields zero matching Transaction records, THEN THE History_Module SHALL present an empty Transaction_List together with an empty-state indication informing the user that no transactions match the selected Sync_Status.

### Requirement 7: Filter by Time

**User Story:** As a user, I want to filter transactions by time range, so that I can review activity for a specific period.

#### Acceptance Criteria

1. THE History_Module SHALL present a Filter_Chip that activates a Time_Filter.
2. WHEN a Time_Filter range is activated, THE History_Module SHALL restrict the Transaction_List to Transaction records whose timestamp is greater than or equal to the range start AND less than or equal to the range end.
3. IF the activated Time_Filter range has a start that is greater than its end, THEN THE History_Module SHALL treat the range as matching no Transaction records and SHALL display an empty-state indication.
4. WHEN the Time_Filter is deactivated, THE History_Module SHALL remove the Time_Filter restriction from the Transaction_List.
5. IF the activated Time_Filter range yields zero matching Transaction records, THEN THE History_Module SHALL display an empty-state indication communicating that no Transaction records match the active filter.

### Requirement 8: Combine Active Filters

**User Story:** As a user, I want multiple filters to work together, so that I can narrow the list precisely.

#### Acceptance Criteria

1. WHILE two or more selections are activated within a single filter dimension, THE History_Module SHALL treat a Transaction record as matching that dimension when it matches any one of the activated selections in that dimension, combining selections within the dimension with OR.
2. WHILE two or more filter dimensions are active in the Filter_Set, THE History_Module SHALL restrict the Transaction_List to Transaction records that match every active filter dimension, combining the dimensions with AND.
3. THE History_Module SHALL include a Transaction record in the Transaction_List if and only if, for every active filter dimension, the Transaction record matches at least one activated selection in that dimension.
4. WHEN the Filter_Set is empty, THE History_Module SHALL present all Transaction records in the Transaction_List.

### Requirement 9: Sort the Transaction List

**User Story:** As a user, I want to sort the list by time or amount, so that I can prioritize the transactions most relevant to me.

#### Acceptance Criteria

1. THE History_Module SHALL allow selection of exactly one Sort_Order from Time-Newest-First, Time-Oldest-First, Amount-Largest-First, and Amount-Smallest-First.
2. WHEN the history screen is first displayed, THE History_Module SHALL apply Time-Newest-First as the default Sort_Order.
3. WHEN the Time-Newest-First Sort_Order is selected, THE History_Module SHALL order the Transaction_List by timestamp in descending order.
4. WHEN the Time-Oldest-First Sort_Order is selected, THE History_Module SHALL order the Transaction_List by timestamp in ascending order.
5. WHEN the Amount-Largest-First Sort_Order is selected, THE History_Module SHALL order the Transaction_List by Amount in descending order, and for Transaction records with equal Amount SHALL order them by timestamp in descending order.
6. WHEN the Amount-Smallest-First Sort_Order is selected, THE History_Module SHALL order the Transaction_List by Amount in ascending order, and for Transaction records with equal Amount SHALL order them by timestamp in descending order.
7. WHEN the Sort_Order changes, THE History_Module SHALL preserve the active Filter_Set.
8. WHILE the Transaction_List contains no Transaction records, THE History_Module SHALL keep the Transaction_List empty under any Sort_Order.

### Requirement 10: Display Dynamic Summary Card

**User Story:** As a user, I want a summary that reflects my current filters, so that I can see totals for exactly the transactions I am viewing.

#### Acceptance Criteria

1. WHEN the history screen is displayed, THE History_Module SHALL display the Summary_Card computed over the Filtered_Set.
2. THE History_Module SHALL compute the Summary_Card income total as the sum of the Amount of each Transaction in the Filtered_Set whose Transaction_Type is INCOME, and the Summary_Card expense total as the sum of the Amount of each Transaction in the Filtered_Set whose Transaction_Type is EXPENSE, and SHALL display the income total and the expense total as separate values.
3. THE History_Module SHALL compute and display the Summary_Card net total as the income total minus the expense total.
4. THE History_Module SHALL exclude every Transaction whose Transaction_Type is TRANSFER from the income total, the expense total, and the net total.
5. WHEN the Filter_Set changes, THE History_Module SHALL recompute and display the Summary_Card income total, expense total, and net total over the resulting Filtered_Set.
6. WHEN a Transaction record is added to or changed in the Transactions_Store, THE History_Module SHALL recompute and display the Summary_Card income total, expense total, and net total over the resulting Filtered_Set.
7. WHEN the Filtered_Set contains no Transaction records, THE History_Module SHALL display the Summary_Card income total as zero, the expense total as zero, and the net total as zero.

### Requirement 11: Display Per-Row Synchronization Indicator

**User Story:** As a user, I want each transaction to show whether it is synced, so that I know which records have reached the backend.

#### Acceptance Criteria

1. WHEN a Transaction whose `is_synced` value is 1 is displayed, THE History_Module SHALL display the Sync_Indicator in the synced state on the corresponding Transaction_Row, visually distinct from the pending state.
2. WHEN a Transaction whose `is_synced` value is 0 is displayed, THE History_Module SHALL display the Sync_Indicator in the pending state on the corresponding Transaction_Row, visually distinct from the synced state.
3. WHEN the `is_synced` value of a displayed Transaction changes, THE History_Module SHALL update the Sync_Indicator on the corresponding Transaction_Row, leaving all other displayed Transaction_Rows unchanged.
4. IF a displayed Transaction has an `is_synced` value other than 0 or 1, THEN THE History_Module SHALL display the Sync_Indicator in the pending state on the corresponding Transaction_Row.

### Requirement 12: Preserve Read-Only Boundary

**User Story:** As a user, I want the history view to only read my data, so that reviewing transactions never accidentally changes them.

#### Acceptance Criteria

1. WHILE the history screen is displayed, THE History_Module SHALL read Transaction records from the Transactions_Store without modifying any Transaction record.
2. WHILE the history screen is displayed, THE History_Module SHALL leave the Transactions_Store unchanged when applying filters, sort orders, or aggregation.
3. WHEN the user has performed a sequence of read, filter, sort, and aggregation operations, THE History_Module SHALL leave the count of Transaction records and the field values of every Transaction record in the Transactions_Store unchanged.
4. IF a read operation from the Transactions_Store fails, THEN THE History_Module SHALL surface an error indication without altering any Transaction record in the Transactions_Store.
