package com.alx.moneytracker.ui.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alx.moneytracker.data.repository.MetadataRepository
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.CategoryPatch
import com.alx.moneytracker.domain.TransactionType
import com.alx.moneytracker.domain.ValidationError
import com.alx.moneytracker.domain.ValidationResult
import com.alx.moneytracker.domain.logic.MetadataValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A UI intent for the category management surface (Scope 3).
 *
 * Each event names a category management action; the ViewModel validates it via
 * [MetadataValidator] before delegating persistence to [MetadataRepository]. A category
 * [TransactionType] is never TRANSFER — a submitted TRANSFER is rejected as
 * [ValidationError.TYPE_NOT_PERMITTED] (Requirements 7.3, 9.3).
 */
sealed interface CategoryManagementEvent {

    /**
     * Create a category with the submitted [name], [type], and optional [icon].
     *
     * @property icon Optional icon identifier; null when none was submitted (Requirement 7.4).
     */
    data class CreateCategory(
        val name: String,
        val type: TransactionType,
        val icon: String? = null
    ) : CategoryManagementEvent

    /**
     * Apply a partial update to the category [categoryId]. A null field means "leave unchanged"
     * (Requirement 9.1); only the submitted fields are validated and persisted.
     */
    data class UpdateCategory(
        val categoryId: Long,
        val name: String? = null,
        val type: TransactionType? = null,
        val icon: String? = null
    ) : CategoryManagementEvent

    /** Archive (soft-delete) the category [categoryId] (Requirement 10). */
    data class ArchiveCategory(val categoryId: Long) : CategoryManagementEvent

    /** Clears the current [CategoryManagementUiState.error] after the UI has surfaced it. */
    data object ErrorConsumed : CategoryManagementEvent
}

/**
 * Immutable UI state for the category management screen.
 *
 * Active categories are partitioned by [TransactionType] into [income] and [expense] groups
 * (Requirement 8 grouping). [error] carries a rejected validation reason for inline display;
 * [operationFailed] flags a persistence failure so the UI can surface a non-blocking error while
 * the store is left in its prior state (Requirement 15.4).
 *
 * @property income Active INCOME categories, from `observeCategories()`.
 * @property expense Active EXPENSE categories, from `observeCategories()`.
 * @property error The validation reason for the most recent rejected create/update, or null.
 * @property operationFailed True when the most recent persistence attempt returned a failure.
 */
data class CategoryManagementUiState(
    val income: List<Category> = emptyList(),
    val expense: List<Category> = emptyList(),
    val error: ValidationError? = null,
    val operationFailed: Boolean = false
) {
    companion object {
        /** The state shown before the first category emission arrives. */
        val Initial = CategoryManagementUiState()
    }
}

/**
 * Owns and exposes the single immutable [CategoryManagementUiState] for the category management
 * screen (MVVM, Scope 3).
 *
 * [uiState] is built from [MetadataRepository.observeCategories] combined with an in-memory
 * transient-signal stream ([error]/[operationFailed]). The active categories are partitioned on
 * [Category.type] into INCOME and EXPENSE groups (Requirement 8.1); because observation is
 * reactive, a create / update / archive re-emits the categories stream and `combine` recomputes
 * the groups automatically without a manual refresh (Requirement 8.4, AGENTS.md Data Rule 2).
 *
 * On [CategoryManagementEvent.CreateCategory] / [CategoryManagementEvent.UpdateCategory] the
 * submitted fields are validated via [MetadataValidator] before any persistence; an invalid
 * submission (including a TRANSFER [TransactionType]) sets [CategoryManagementUiState.error] and
 * the repository is never called, leaving the store unchanged (Requirements 7.3, 9.2, 9.3). A
 * repository [Result.failure] sets [CategoryManagementUiState.operationFailed].
 *
 * The pipeline runs on [Dispatchers.IO] via [flowOn] so persistence never blocks the main thread;
 * the resulting [StateFlow] is observed by Compose on the main thread. The ViewModel reaches only
 * the metadata write surface of [MetadataRepository] and never touches the `transactions` table
 * (Requirement 15).
 */
class CategoryManagementViewModel(
    private val repository: MetadataRepository
) : ViewModel() {

    /**
     * Transient in-memory signals (validation error, last persistence failure) that are not part
     * of the persisted category stream. They are combined into [uiState] so the UI can render them
     * alongside the reactive category groups.
     */
    private val signals = MutableStateFlow(Signals())

    val uiState: StateFlow<CategoryManagementUiState> = combine(
        repository.observeCategories(),
        signals
    ) { categories, signal ->
        CategoryManagementUiState(
            income = categories.filter { it.type == TransactionType.INCOME },
            expense = categories.filter { it.type == TransactionType.EXPENSE },
            error = signal.error,
            operationFailed = signal.operationFailed
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryManagementUiState.Initial)

    /**
     * Reduces a UI [event] into a validation/persistence action.
     *
     * Create/update validate via [MetadataValidator] before persisting; an [ValidationResult.Invalid]
     * sets the error and skips the repository (Requirements 7.3, 9.2, 9.3). Archive delegates
     * directly. [CategoryManagementEvent.ErrorConsumed] clears the transient signals.
     */
    fun onEvent(event: CategoryManagementEvent) {
        when (event) {
            is CategoryManagementEvent.CreateCategory -> viewModelScope.launch {
                when (val result = MetadataValidator.validateCategory(event.name, event.type, event.icon)) {
                    is ValidationResult.Invalid -> setError(result.reason)
                    is ValidationResult.Valid -> repository.createCategory(result.value).handle()
                }
            }

            is CategoryManagementEvent.UpdateCategory -> viewModelScope.launch {
                when (val patch = buildPatch(event)) {
                    is ValidationResult.Invalid -> setError(patch.reason)
                    is ValidationResult.Valid -> repository.updateCategory(event.categoryId, patch.value).handle()
                }
            }

            is CategoryManagementEvent.ArchiveCategory -> viewModelScope.launch {
                repository.archiveCategory(event.categoryId).handle()
            }

            CategoryManagementEvent.ErrorConsumed ->
                signals.update { Signals() }
        }
    }

    /**
     * Builds a validated [CategoryPatch] from the non-null fields of an [event].
     *
     * Only submitted fields are validated and carried: a submitted name is trimmed and length-checked
     * against [MetadataValidator.CATEGORY_NAME_MAX] (Requirement 9.2), and a submitted TRANSFER type
     * is rejected as [ValidationError.TYPE_NOT_PERMITTED] (Requirement 9.3). Omitted (null) fields are
     * left unchanged (Requirement 9.1).
     */
    private fun buildPatch(
        event: CategoryManagementEvent.UpdateCategory
    ): ValidationResult<CategoryPatch> {
        val trimmedName = event.name?.trim()
        if (event.name != null) {
            when {
                trimmedName!!.isEmpty() -> return ValidationResult.Invalid(ValidationError.NAME_EMPTY)
                trimmedName.length > MetadataValidator.CATEGORY_NAME_MAX ->
                    return ValidationResult.Invalid(ValidationError.NAME_TOO_LONG)
            }
        }
        if (event.type == TransactionType.TRANSFER) {
            return ValidationResult.Invalid(ValidationError.TYPE_NOT_PERMITTED)
        }
        return ValidationResult.Valid(
            CategoryPatch(name = trimmedName, type = event.type, icon = event.icon)
        )
    }

    /** Sets the transient validation [error] and clears any prior persistence-failure flag. */
    private fun setError(error: ValidationError) {
        signals.update { it.copy(error = error, operationFailed = false) }
    }

    /**
     * Maps a persistence [Result] into the transient signals: success clears both signals; failure
     * sets [Signals.operationFailed] while leaving the store in its prior state (Requirement 15.4).
     */
    private fun Result<Unit>.handle() {
        signals.update {
            if (isSuccess) Signals() else it.copy(operationFailed = true)
        }
    }

    /**
     * The transient, non-persisted portion of the UI state combined into [uiState].
     *
     * @property error The most recent rejected-validation reason, or null.
     * @property operationFailed True when the most recent persistence attempt failed.
     */
    private data class Signals(
        val error: ValidationError? = null,
        val operationFailed: Boolean = false
    )
}
