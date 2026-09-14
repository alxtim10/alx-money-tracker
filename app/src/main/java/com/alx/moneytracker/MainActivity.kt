package com.alx.moneytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.alx.moneytracker.ui.theme.AppTheme
import com.alx.moneytracker.data.local.DatabaseProvider
import com.alx.moneytracker.data.repository.RoomTransactionRepository
import com.alx.moneytracker.ui.input.TransactionInputScreen
import com.alx.moneytracker.ui.input.TransactionInputViewModel
import com.alx.moneytracker.ui.input.TransactionInputViewModelFactory

/**
 * Single entry-point activity that wires the Transaction Input Engine together with manual DI
 * (Requirement 12): Room database → DAOs → repository → ViewModel → Compose UI.
 *
 * The database is obtained from [DatabaseProvider] (process-wide singleton), a
 * [RoomTransactionRepository] is constructed from its four DAOs, and the [TransactionInputViewModel]
 * is created through [TransactionInputViewModelFactory] via [viewModels] so it survives
 * configuration changes. The stateful [TransactionInputScreen] overload binds directly to the
 * ViewModel's `uiState` and routes UI actions back to `onEvent`.
 *
 * Theming uses the Compose [AppTheme] (Cornflower Blue `#6495ED` accent) rather than an XML theme,
 * so no `res/values` theme files are required.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: TransactionInputViewModel by viewModels {
        val database = DatabaseProvider.get(applicationContext)
        val repository = RoomTransactionRepository(
            transactionDao = database.transactionDao(),
            walletDao = database.walletDao(),
            categoryDao = database.categoryDao(),
            quickPresetDao = database.quickPresetDao()
        )
        TransactionInputViewModelFactory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                TransactionInputScreen(viewModel = viewModel)
            }
        }
    }
}
