package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The submit control for the transaction entry. Its enabled state is driven purely by [enabled]
 * (which the caller derives from the validated UI state), and a tap emits [onSubmit]
 * (Requirement 7.1). Placed by the screen in the lower region for one-handed reach (Requirement 8).
 */
@Composable
fun SubmitButton(
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onSubmit,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("submit_button")
    ) {
        Text("Save Transaction")
    }
}
