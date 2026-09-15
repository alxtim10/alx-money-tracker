package com.alx.moneytracker.ui.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.ValidationError

/**
 * The small, fixed palette of icon identifiers offered by the optional icon picker.
 *
 * A category [Category.icon] is a free-form string identifier that may be null (none chosen —
 * Requirement 7.4); this palette gives the create/edit flow a bounded, tappable set to choose from
 * while still allowing "None". The values are stored verbatim as the icon identifier.
 */
private val ICON_PALETTE: List<String> = listOf(
    "🍔", "🛒", "🚗", "🏠", "💡", "🎁", "💊", "🎮", "✈️", "💰", "📚", "☕"
)

/**
 * Stateless Category Management screen (Scope 3).
 *
 * Renders active categories from [CategoryManagementUiState] in two grouped sections — **INCOME**
 * and **EXPENSE** (Requirement 8.1, 8.3) — in the scrollable upper region, and anchors the
 * create / edit flow in a raised, top-rounded surface in the LOWER region for one-handed reach
 * (AGENTS.md UI Rule 2). Every interaction is forwarded as a [CategoryManagementEvent] via
 * [onEvent]; the screen holds no business logic beyond gathering the draft fields.
 *
 * The create / edit flow offers a name text field (OS text keyboard — names are text, not numbers,
 * so UI Rule 1's numpad requirement does not apply here), an **INCOME / EXPENSE** single-choice
 * control that **never** offers TRANSFER (Requirements 7.3, 9.3), and an optional icon picker whose
 * selection is nullable (Requirement 7.4). Submitting with no row in edit mode dispatches
 * [CategoryManagementEvent.CreateCategory] (Requirement 7.1); submitting while editing dispatches
 * [CategoryManagementEvent.UpdateCategory] (Requirement 9.1). Per-row **Edit** loads the row into
 * the draft and **Archive** dispatches [CategoryManagementEvent.ArchiveCategory].
 *
 * A validation [CategoryManagementUiState.error] or an [CategoryManagementUiState.operationFailed]
 * flag is surfaced once through a [SnackbarHost], then cleared via
 * [CategoryManagementEvent.ErrorConsumed] so the transient message does not re-show.
 */
@Composable
fun CategoryManagementScreen(
    state: CategoryManagementUiState,
    onEvent: (CategoryManagementEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Draft fields for the create/edit flow. `editingId == null` means "create".
    var editingId by remember { mutableStateOf<Long?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftType by remember { mutableStateOf(TransactionType.EXPENSE) }
    var draftIcon by remember { mutableStateOf<String?>(null) }

    fun resetDraft() {
        editingId = null
        draftName = ""
        draftType = TransactionType.EXPENSE
        draftIcon = null
    }

    // Surface and consume the transient validation error / persistence-failure signal once.
    LaunchedEffect(state.error, state.operationFailed) {
        val message = when {
            state.error != null -> state.error.toMessage()
            state.operationFailed -> "Couldn't save. Please try again."
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onEvent(CategoryManagementEvent.ErrorConsumed)
        }
    }

    Scaffold(
        modifier = modifier.testTag("category_management_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Upper region: the two grouped category sections. Weighted so it yields space to the
            // lower create/edit cluster.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .testTag("category_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { GroupHeader(title = "INCOME", modifier = Modifier.testTag("group_income")) }
                items(state.income, key = { "income_${it.id}" }) { category ->
                    CategoryRow(
                        category = category,
                        onEdit = {
                            editingId = category.id
                            draftName = category.name
                            draftType = category.type
                            draftIcon = category.icon.ifBlank { null }
                        },
                        onArchive = { onEvent(CategoryManagementEvent.ArchiveCategory(category.id)) }
                    )
                }

                item { GroupHeader(title = "EXPENSE", modifier = Modifier.testTag("group_expense")) }
                items(state.expense, key = { "expense_${it.id}" }) { category ->
                    CategoryRow(
                        category = category,
                        onEdit = {
                            editingId = category.id
                            draftName = category.name
                            draftType = category.type
                            draftIcon = category.icon.ifBlank { null }
                        },
                        onArchive = { onEvent(CategoryManagementEvent.ArchiveCategory(category.id)) }
                    )
                }
            }

            // Lower region: raised, top-rounded create/edit cluster (UI Rule 2).
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                CategoryEditor(
                    editing = editingId != null,
                    name = draftName,
                    onNameChange = { draftName = it },
                    type = draftType,
                    onTypeChange = { draftType = it },
                    icon = draftIcon,
                    onIconChange = { draftIcon = it },
                    onSubmit = {
                        val id = editingId
                        if (id == null) {
                            onEvent(
                                CategoryManagementEvent.CreateCategory(
                                    name = draftName,
                                    type = draftType,
                                    icon = draftIcon
                                )
                            )
                        } else {
                            onEvent(
                                CategoryManagementEvent.UpdateCategory(
                                    categoryId = id,
                                    name = draftName,
                                    type = draftType,
                                    icon = draftIcon
                                )
                            )
                        }
                        resetDraft()
                    },
                    onCancel = { resetDraft() }
                )
            }
        }
    }
}

/** A quiet section header separating the INCOME and EXPENSE groups. */
@Composable
private fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/**
 * A single category row: leading icon (if any), the name, and trailing **Edit** / **Archive**
 * actions (Requirements 8.3, 9.1, 10.1).
 */
@Composable
private fun CategoryRow(
    category: Category,
    onEdit: () -> Unit,
    onArchive: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("category_row_${category.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (category.icon.isNotBlank()) {
                Text(text = category.icon, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onEdit,
                modifier = Modifier.testTag("edit_${category.id}")
            ) { Text("Edit") }
            TextButton(
                onClick = onArchive,
                modifier = Modifier.testTag("archive_${category.id}")
            ) { Text("Archive") }
        }
    }
}

/**
 * The create / edit flow anchored in the lower region.
 *
 * Offers the name text field (OS text keyboard), the INCOME/EXPENSE single-choice control (never
 * TRANSFER), the optional icon picker, and the primary **Create** / **Save** action plus a
 * **Cancel** shown only while editing.
 */
@Composable
private fun CategoryEditor(
    editing: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    type: TransactionType,
    onTypeChange: (TransactionType) -> Unit,
    icon: String?,
    onIconChange: (String?) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("category_editor"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (editing) "Edit category" else "New category",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            singleLine = true,
            label = { Text("Name") },
            placeholder = { Text("e.g. Makan") },
            shape = RoundedCornerShape(16.dp),
            // Category names are text, entered via the OS keyboard (numpad is for numeric input
            // only — UI Rule 1).
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("category_name_field")
        )

        // INCOME / EXPENSE single-choice control — TRANSFER is never offered (Requirements 7.3, 9.3).
        CategoryTypeSelector(selectedType = type, onTypeSelected = onTypeChange)

        // Optional icon picker: nullable selection with a leading "None" chip (Requirement 7.4).
        IconPicker(selectedIcon = icon, onIconSelected = onIconChange)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (editing) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("category_cancel")
                ) { Text("Cancel") }
            }
            Button(
                onClick = onSubmit,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("category_submit")
            ) { Text(if (editing) "Save" else "Create") }
        }
    }
}

/**
 * A single-choice pill control over the two permitted category types — INCOME and EXPENSE only.
 * TRANSFER is deliberately never rendered as an option (Requirements 7.3, 9.3).
 */
@Composable
private fun CategoryTypeSelector(
    selectedType: TransactionType,
    onTypeSelected: (TransactionType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("category_type_selector"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(TransactionType.INCOME, TransactionType.EXPENSE).forEach { type ->
            val selected = type == selectedType
            val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                color = bg,
                contentColor = fg,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onTypeSelected(type) })
                    .testTag("category_type_${type.name}")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (type == TransactionType.INCOME) "Income" else "Expense",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * The optional icon picker: a horizontally scrollable row of icon chips plus a leading **None**
 * chip. Selecting an icon carries a non-null identifier; selecting **None** carries null so the
 * category persists with no icon (Requirement 7.4).
 */
@Composable
private fun IconPicker(
    selectedIcon: String?,
    onIconSelected: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("icon_picker"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconChip(
            label = "None",
            selected = selectedIcon == null,
            onClick = { onIconSelected(null) },
            testTag = "icon_none"
        )
        ICON_PALETTE.forEach { icon ->
            IconChip(
                label = icon,
                selected = selectedIcon == icon,
                onClick = { onIconSelected(icon) },
                testTag = "icon_$icon"
            )
        }
    }
}

@Composable
private fun IconChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        contentColor = fg,
        shape = CircleShape,
        modifier = Modifier
            .size(44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Maps a [ValidationError] into a short, human-readable message for the snackbar. */
private fun ValidationError.toMessage(): String = when (this) {
    ValidationError.NAME_EMPTY -> "Name can't be empty."
    ValidationError.NAME_TOO_LONG -> "Name is too long."
    ValidationError.TYPE_NOT_PERMITTED -> "Transfer isn't a valid category type."
    ValidationError.BALANCE_OUT_OF_RANGE -> "Balance is out of range."
    ValidationError.AMOUNT_OUT_OF_RANGE -> "Amount is out of range."
    ValidationError.LABEL_EMPTY -> "Label can't be empty."
    ValidationError.LABEL_TOO_LONG -> "Label is too long."
}

/**
 * Stateful overload binding the screen to a [CategoryManagementViewModel].
 *
 * Collects [CategoryManagementViewModel.uiState] and delegates to the stateless overload, routing
 * UI events back through [CategoryManagementViewModel.onEvent].
 */
@Composable
fun CategoryManagementScreen(
    viewModel: CategoryManagementViewModel,
    modifier: Modifier = Modifier
) {
    val state: State<CategoryManagementUiState> = viewModel.uiState.collectAsState()
    CategoryManagementScreen(
        state = state.value,
        onEvent = viewModel::onEvent,
        modifier = modifier
    )
}
