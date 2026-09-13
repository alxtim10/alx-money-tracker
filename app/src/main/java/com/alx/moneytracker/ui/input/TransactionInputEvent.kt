package com.alx.moneytracker.ui.input

import com.alx.moneytracker.domain.TransactionType

/**
 * All user actions the Transaction Input UI can dispatch to the ViewModel.
 *
 * The stateless Compose UI forwards interactions as these events; the ViewModel
 * interprets them and produces a new [TransactionInputUiState].
 */
sealed interface TransactionInputEvent {
    /** A numpad digit key (0..9) was tapped (Requirement 1.2). */
    data class DigitPressed(val digit: Int) : TransactionInputEvent

    /** The numpad delete key was tapped (Requirements 1.3, 1.4). */
    data object DeletePressed : TransactionInputEvent

    /** A quick-preset chip with the given value was tapped (Requirement 2). */
    data class PresetTapped(val value: Long) : TransactionInputEvent

    /** A transaction type was selected (Requirement 3). */
    data class TypeSelected(val type: TransactionType) : TransactionInputEvent

    /** A source wallet was selected (Requirement 4.2). */
    data class SourceWalletSelected(val walletId: Long) : TransactionInputEvent

    /** A destination wallet was selected (TRANSFER only, Requirement 4.3). */
    data class DestWalletSelected(val walletId: Long) : TransactionInputEvent

    /** A category was selected (Requirement 5). */
    data class CategorySelected(val categoryId: Long) : TransactionInputEvent

    /** The note text changed (Requirement 6). */
    data class NoteChanged(val text: String) : TransactionInputEvent

    /** The submit control was activated (Requirement 9). */
    data object Submit : TransactionInputEvent

    /** The UI has shown and consumed the current error message (Requirement 9.5). */
    data object ErrorConsumed : TransactionInputEvent
}
