package com.alx.moneytracker.domain

/**
 * A tappable preset representing a fixed monetary value (for example, +10.000) that adds its
 * [amount] to the running amount when tapped.
 *
 * @property id Stable preset identifier.
 * @property amount The value added to the running amount, in the smallest currency unit.
 * @property label Human-readable chip label (for example, "+10k").
 */
data class QuickPreset(
    val id: Long,
    val amount: Long,
    val label: String
)
