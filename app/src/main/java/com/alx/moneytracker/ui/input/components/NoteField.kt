package com.alx.moneytracker.ui.input.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Optional free-text note field.
 *
 * This is a standard Material 3 [OutlinedTextField]. By default a text field only requests the
 * operating system keyboard once it gains focus (i.e. when tapped), which satisfies Requirements
 * 6.1-6.3: the keyboard stays hidden until the field is tapped, then appears for entry, and the
 * field accepts arbitrary free text.
 *
 * Stateless: [note] is the current value and every edit is forwarded via [onNoteChanged].
 */
@Composable
fun NoteField(
    note: String,
    onNoteChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = note,
        onValueChange = onNoteChanged,
        label = { Text("Note (optional)") },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("note_field")
    )
}
