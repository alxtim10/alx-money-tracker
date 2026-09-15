package com.alx.moneytracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alx.moneytracker.data.repository.HistoryRepository

/**
 * Manual-DI [ViewModelProvider.Factory] for [TransactionHistoryViewModel] (Scope 2).
 *
 * Mirrors Scope 1's `TransactionInputViewModelFactory` convention: it supplies the ViewModel's only
 * dependency — the read-only [HistoryRepository] built from the shared Room database at the
 * activity level — so the ViewModel survives configuration changes when created via `viewModels`.
 * The repository surface exposes only `observe*` methods, so the read-only boundary (Requirement 12)
 * is preserved by construction.
 */
class TransactionHistoryViewModelFactory(
    private val repository: HistoryRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TransactionHistoryViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return TransactionHistoryViewModel(repository) as T
    }
}
