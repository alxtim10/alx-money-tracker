package com.alx.moneytracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.alx.moneytracker.ui.theme.AppTheme
import com.alx.moneytracker.data.local.DatabaseProvider
import com.alx.moneytracker.data.repository.RoomHistoryRepository
import com.alx.moneytracker.data.repository.RoomMetadataRepository
import com.alx.moneytracker.data.repository.RoomTransactionRepository
import com.alx.moneytracker.ui.history.HistoryScreen
import com.alx.moneytracker.ui.history.TransactionHistoryViewModel
import com.alx.moneytracker.ui.history.TransactionHistoryViewModelFactory
import com.alx.moneytracker.ui.input.TransactionInputScreen
import com.alx.moneytracker.ui.input.TransactionInputViewModel
import com.alx.moneytracker.ui.input.TransactionInputViewModelFactory
import com.alx.moneytracker.ui.metadata.CategoryManagementScreen
import com.alx.moneytracker.ui.metadata.CategoryManagementViewModel
import com.alx.moneytracker.ui.metadata.CategoryManagementViewModelFactory
import com.alx.moneytracker.ui.metadata.QuickPresetManagementScreen
import com.alx.moneytracker.ui.metadata.QuickPresetManagementViewModel
import com.alx.moneytracker.ui.metadata.QuickPresetManagementViewModelFactory
import com.alx.moneytracker.ui.metadata.WalletManagementScreen
import com.alx.moneytracker.ui.metadata.WalletManagementViewModel
import com.alx.moneytracker.ui.metadata.WalletManagementViewModelFactory

/**
 * Single entry-point activity that wires every scope together with manual DI (Requirement 12):
 * Room database → DAOs → repository → ViewModel → Compose UI.
 *
 * The database is obtained from [DatabaseProvider] (process-wide singleton) and every ViewModel is
 * built from the *same* [com.alx.moneytracker.data.local.AppDatabase] so the write path (Scope 1),
 * the read path (Scope 2), and the metadata management path (Scope 3) observe one shared store — a
 * transaction saved on the input screen re-emits on the history screen's reactive streams, and a
 * wallet/category/preset edited on a Scope 3 screen re-emits into Scope 1 & 2
 * (Requirements 1.1, 2.1, AGENTS.md Data Rule 2).
 *
 * - Scope 1's [TransactionInputViewModel] is created through [TransactionInputViewModelFactory] over
 *   a [RoomTransactionRepository] (four DAOs, includes the atomic write path).
 * - Scope 2's [TransactionHistoryViewModel] is created through [TransactionHistoryViewModelFactory]
 *   over a read-only [RoomHistoryRepository] (three observe-only DAOs).
 * - Scope 3's three management ViewModels are created through their respective factories over a
 *   single [RoomMetadataRepository] (three metadata DAOs, **no** `TransactionDao` — the
 *   transaction-untouched boundary of Data Rule 3 / Requirement 15 holds by construction).
 *
 * All are obtained via [viewModels] so they survive configuration changes. A minimal Material 3
 * bottom [NavigationBar] switches between the destinations — no navigation library is introduced
 * (matching Scope 1's dependency-light convention). The primary interaction cluster stays reachable
 * at the bottom of the screen (AGENTS.md UI Rule 2).
 */
class MainActivity : ComponentActivity() {

    private val inputViewModel: TransactionInputViewModel by viewModels {
        val database = DatabaseProvider.get(applicationContext)
        val repository = RoomTransactionRepository(
            transactionDao = database.transactionDao(),
            walletDao = database.walletDao(),
            categoryDao = database.categoryDao(),
            quickPresetDao = database.quickPresetDao()
        )
        TransactionInputViewModelFactory(repository)
    }

    private val historyViewModel: TransactionHistoryViewModel by viewModels {
        val database = DatabaseProvider.get(applicationContext)
        val repository = RoomHistoryRepository(
            transactionDao = database.transactionDao(),
            walletDao = database.walletDao(),
            categoryDao = database.categoryDao()
        )
        TransactionHistoryViewModelFactory(repository)
    }

    private val walletManagementViewModel: WalletManagementViewModel by viewModels {
        WalletManagementViewModelFactory(metadataRepository())
    }

    private val categoryManagementViewModel: CategoryManagementViewModel by viewModels {
        CategoryManagementViewModelFactory(metadataRepository())
    }

    private val quickPresetManagementViewModel: QuickPresetManagementViewModel by viewModels {
        QuickPresetManagementViewModelFactory(metadataRepository())
    }

    /**
     * Builds a [RoomMetadataRepository] over the shared [DatabaseProvider] database using only the
     * three metadata DAOs (no `TransactionDao`), so all three Scope 3 ViewModels share the same
     * metadata write/observe surface (Requirements 2.1, 8.1, 12.1).
     */
    private fun metadataRepository(): RoomMetadataRepository {
        val database = DatabaseProvider.get(applicationContext)
        return RoomMetadataRepository(
            walletDao = database.walletDao(),
            categoryDao = database.categoryDao(),
            quickPresetDao = database.quickPresetDao()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainScaffold(
                    inputViewModel = inputViewModel,
                    historyViewModel = historyViewModel,
                    walletManagementViewModel = walletManagementViewModel,
                    categoryManagementViewModel = categoryManagementViewModel,
                    quickPresetManagementViewModel = quickPresetManagementViewModel
                )
            }
        }
    }
}

/** The top-level destinations reachable from the bottom navigation bar. */
private enum class Destination { INPUT, HISTORY, WALLETS, CATEGORIES, PRESETS }

/**
 * Hosts the bottom [NavigationBar] and swaps the selected destination's screen into the content
 * area. Every ViewModel is long-lived (owned by the activity), so switching tabs preserves each
 * screen's state without a navigation library.
 */
@Composable
private fun MainScaffold(
    inputViewModel: TransactionInputViewModel,
    historyViewModel: TransactionHistoryViewModel,
    walletManagementViewModel: WalletManagementViewModel,
    categoryManagementViewModel: CategoryManagementViewModel,
    quickPresetManagementViewModel: QuickPresetManagementViewModel
) {
    var destination by remember { mutableStateOf(Destination.INPUT) }

    Scaffold(
        modifier = Modifier.testTag("main_scaffold"),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = destination == Destination.INPUT,
                    onClick = { destination = Destination.INPUT },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = { Text("Input") },
                    modifier = Modifier.testTag("nav_input")
                )
                NavigationBarItem(
                    selected = destination == Destination.HISTORY,
                    onClick = { destination = Destination.HISTORY },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("History") },
                    modifier = Modifier.testTag("nav_history")
                )
                NavigationBarItem(
                    selected = destination == Destination.WALLETS,
                    onClick = { destination = Destination.WALLETS },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Wallets") },
                    modifier = Modifier.testTag("nav_wallets")
                )
                NavigationBarItem(
                    selected = destination == Destination.CATEGORIES,
                    onClick = { destination = Destination.CATEGORIES },
                    icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                    label = { Text("Categories") },
                    modifier = Modifier.testTag("nav_categories")
                )
                NavigationBarItem(
                    selected = destination == Destination.PRESETS,
                    onClick = { destination = Destination.PRESETS },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("Presets") },
                    modifier = Modifier.testTag("nav_presets")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (destination) {
                Destination.INPUT -> TransactionInputScreen(viewModel = inputViewModel)
                Destination.HISTORY -> HistoryScreen(viewModel = historyViewModel)
                Destination.WALLETS -> WalletManagementScreen(viewModel = walletManagementViewModel)
                Destination.CATEGORIES -> CategoryManagementScreen(viewModel = categoryManagementViewModel)
                Destination.PRESETS -> QuickPresetManagementScreen(viewModel = quickPresetManagementViewModel)
            }
        }
    }
}
