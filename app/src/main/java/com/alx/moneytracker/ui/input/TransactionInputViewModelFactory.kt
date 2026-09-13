package com.alx.moneytracker.ui.input

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.alx.moneytracker.data.repository.TransactionRepository

/**
 * Manual-DI [ViewModelProvider.Factory] for [TransactionInputViewModel].
 *
 * Supplies the ViewModel's [TransactionRepository] dependency (built from the Room database at the
 * composition/activity level). [TransactionInputViewModel] keeps its `clock` and `idGenerator`
 * defaults for production; they remain injectable directly in tests.
 */
class TransactionInputViewModelFactory(
    private val repository: TransactionRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TransactionInputViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return TransactionInputViewModel(repository) as T
    }
}
