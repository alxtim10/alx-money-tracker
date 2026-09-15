package com.alx.moneytracker.ui.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alx.moneytracker.data.repository.MetadataRepository
import com.alx.moneytracker.domain.PresetPatch
import com.alx.moneytracker.domain.QuickPreset
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
import kotlinx.coroutines.launch

/**
 * A UI event dispatched from the Quick Preset management screen (Scope 3, MVVM).
 *
 * The screen is stateless: it renders [QuickPresetManagementUiState] and dispatches one of these
 * events, which [QuickPresetManagementViewModel.onEvent] validates and (when valid) persists via
 * the [MetadataRepository].
 */
sealed interface QuickPresetManagementEvent {

    /** Create a preset with [amount] (smallest currency unit) and [label] (Requirement 11). */
    data class CreatePreset(val amount: Long, val label: String) : QuickPresetManagementEvent

    /**
     * Apply a partial update to the preset [presetId]. A null [amount] or [label] means "leave
     * that field unchanged"; only submitted (non-null) fields are validated and changed
     * (Requirement 13).
     */
    data class UpdatePreset(
        val presetId: Long,
        val amount: Long? = null,
        val label: String? = null
    ) : QuickPresetManagementEvent

    /** Hard-delete the preset [presetId] (Requirement 14). */
    data class DeletePreset(val presetId: Long) : QuickPresetManagementEvent

    /** Clear a surfaced validation error after the UI has shown it. */
    data object ErrorConsumed : QuickPresetManagementEvent
}

/**
 * Immutable UI state for the Quick Preset management screen.
 *
 * @property presets The current collection of presets from [MetadataRepository.observeQuickPresets]
 *   (Requirement 12).
 * @property error The specific validation failure to surface, or null when the last submission was
 *   valid (Requirements 11.2, 11.3, 13.2, 13.3).
 * @property operationFailed True when the most recent persistence attempt returned
 *   [Result.failure]; the affected store is left in its prior state (Requirement 14).
 */
data class QuickPresetManagementUiState(
    val presets: List<QuickPreset> = emptyList(),
    val error: ValidationError? = null,
    val operationFailed: Boolean = false
) {
    companion object {
        val Initial = QuickPresetManagementUiState()
    }
}

/**
 * Owns and exposes the single immutable [QuickPresetManagementUiState] for the Quick Preset
 * management screen (Scope 3, MVVM).
 *
 * [uiState] is built by `combine`-ing the repository's observable preset stream with in-memory
 * [error]/[operationFailed] streams, so a create / update / delete re-emits the preset list and
 * the state recomputes automatically without a manual refresh (AGENTS.md Data Rule 2). Observation
 * runs on [Dispatchers.IO] via [flowOn]; the resulting [StateFlow] is observed by Compose on the
 * main thread.
 *
 * In [onEvent] each submission is validated via [MetadataValidator] before persisting: an invalid
 * submission sets [QuickPresetManagementUiState.error] and never reaches the repository, and a
 * repository [Result.failure] sets [QuickPresetManagementUiState.operationFailed]. The ViewModel
 * only reaches the metadata write surface of [MetadataRepository] and never touches the
 * `transactions` table (Requirement 15, enforced structurally by the repository type).
 */
class QuickPresetManagementViewModel(
    private val repository: MetadataRepository
) : ViewModel() {

    private val error = MutableStateFlow<ValidationError?>(null)
    private val operationFailed = MutableStateFlow(false)

    val uiState: StateFlow<QuickPresetManagementUiState> = combine(
        repository.observeQuickPresets(),
        error,
        operationFailed
    ) { presets, err, failed ->
        QuickPresetManagementUiState(
            presets = presets,
            error = err,
            operationFailed = failed
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            QuickPresetManagementUiState.Initial
        )

    /**
     * Validates then persists a preset [event]. On a validation failure it sets [error] and does
     * not call the repository (the store is left unchanged); on a repository failure it sets
     * [operationFailed]. [QuickPresetManagementEvent.ErrorConsumed] clears both indications.
     */
    fun onEvent(event: QuickPresetManagementEvent) {
        when (event) {
            is QuickPresetManagementEvent.CreatePreset -> viewModelScope.launch {
                when (val result = MetadataValidator.validatePreset(event.amount, event.label)) {
                    is ValidationResult.Invalid -> setError(result.reason)
                    is ValidationResult.Valid -> repository.createPreset(result.value).handle()
                }
            }

            is QuickPresetManagementEvent.UpdatePreset -> viewModelScope.launch {
                when (val patch = validatePatch(event.amount, event.label)) {
                    is ValidationResult.Invalid -> setError(patch.reason)
                    is ValidationResult.Valid ->
                        repository.updatePreset(event.presetId, patch.value).handle()
                }
            }

            is QuickPresetManagementEvent.DeletePreset -> viewModelScope.launch {
                repository.deletePreset(event.presetId).handle()
            }

            QuickPresetManagementEvent.ErrorConsumed -> {
                error.value = null
                operationFailed.value = false
            }
        }
    }

    /**
     * Builds a [PresetPatch] from the submitted (non-null) fields, validating each independently:
     * a submitted [amount] must be in `1..MAX_MONEY` and a submitted [label] must be a non-empty
     * trimmed label within its bound; omitted (null) fields are left out of the patch and unchanged
     * (Requirement 13). Returns the offending [ValidationError] when any submitted field is invalid.
     */
    private fun validatePatch(amount: Long?, label: String?): ValidationResult<PresetPatch> {
        val validatedLabel: String? = if (label != null) {
            when (val labelResult = MetadataValidator.validatePreset(1L, label)) {
                is ValidationResult.Invalid -> return ValidationResult.Invalid(labelResult.reason)
                is ValidationResult.Valid -> labelResult.value.label
            }
        } else {
            null
        }

        val validatedAmount: Long? = if (amount != null) {
            when (val amountResult = MetadataValidator.validatePreset(amount, "placeholder")) {
                is ValidationResult.Invalid -> return ValidationResult.Invalid(amountResult.reason)
                is ValidationResult.Valid -> amountResult.value.amount
            }
        } else {
            null
        }

        return ValidationResult.Valid(PresetPatch(amount = validatedAmount, label = validatedLabel))
    }

    /** Records the offending [reason] and clears any prior operation-failure flag. */
    private fun setError(reason: ValidationError) {
        error.value = reason
        operationFailed.value = false
    }

    /**
     * Folds a persistence [Result] into state: success clears the error indications; failure sets
     * [operationFailed] leaving the store in its prior state (Requirement 14).
     */
    private fun Result<Unit>.handle() {
        fold(
            onSuccess = {
                error.value = null
                operationFailed.value = false
            },
            onFailure = {
                operationFailed.value = true
            }
        )
    }
}
