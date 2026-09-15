package com.alx.moneytracker.ui.metadata

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alx.moneytracker.data.repository.MetadataRepository

/**
 * Manual-DI [ViewModelProvider.Factory] for [QuickPresetManagementViewModel] (Scope 3).
 *
 * Supplies the ViewModel's [MetadataRepository] dependency (built from the Room database at the
 * activity level), matching Scope 1 & 2's factory convention so no DI framework is introduced.
 */
class QuickPresetManagementViewModelFactory(
    private val repository: MetadataRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(QuickPresetManagementViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return QuickPresetManagementViewModel(repository) as T
    }
}
