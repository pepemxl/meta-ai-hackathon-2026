package com.ppcoach.tracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.SavedPitch
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

/** What the coach picked in [PitchPicker]. */
sealed interface PitchChoice {
    data class Saved(val pitch: SavedPitch) : PitchChoice
    /** A pitch not saved, of size 11 or 7 (typical meters). */
    data class Unsaved(val size: Int) : PitchChoice
    data object New : PitchChoice
    data class Edit(val pitch: SavedPitch) : PitchChoice
}

/**
 * The pitch dropdown: the saved pitches (most recently used first), an
 * unsaved 11 or 7, "New pitch…" and, on a saved one, "Edit pitch". Below it,
 * the meters it uses and whether they were measured.
 */
@Composable
fun PitchPicker(pitches: List<SavedPitch>, selected: SavedPitch?, size: Int, onChoice: (PitchChoice) -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var open by remember { mutableStateOf(false) }
    val label = selected?.let { s.pitchOption(it.name, it.size) } ?: s.pitchUnsaved(size)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s.pitchLabel, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.textSecondary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .background(c.surface).clickable { open = true }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null, tint = c.textSecondary)
            }
            DropdownMenu(open, { open = false }, Modifier.background(c.surface)) {
                for (p in pitches) {
                    DropdownMenuItem(
                        text = { Text(s.pitchOption(p.name, p.size), color = c.textPrimary) },
                        onClick = { open = false; onChoice(PitchChoice.Saved(p)) },
                    )
                }
                if (pitches.isNotEmpty()) HorizontalDivider()
                for (k in listOf(11, 7)) {
                    DropdownMenuItem(text = { Text(s.pitchUnsaved(k), color = c.textSecondary) }, onClick = { open = false; onChoice(PitchChoice.Unsaved(k)) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(s.newPitch, color = c.accent) }, onClick = { open = false; onChoice(PitchChoice.New) })
                if (selected != null) {
                    DropdownMenuItem(text = { Text(s.editPitch, color = c.accent) }, onClick = { open = false; onChoice(PitchChoice.Edit(selected)) })
                }
            }
        }
        val (l, w) = selected?.dimensions ?: com.ppcoach.tracker.data.PitchTemplate.dimensions(size)
        Text(
            if (selected?.measured == true) s.pitchMeasured(l, w) else s.pitchTypical(l, w),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected?.measured == true) c.textSecondary else c.warning,
        )
    }
}

/**
 * Creates or edits a saved pitch: name, 11 or 7 and, optionally, length and
 * width in meters (measured once, reused on every visit). [onDelete] only
 * when editing.
 */
@Composable
fun PitchEditorDialog(initial: SavedPitch, onDismiss: () -> Unit, onSave: (SavedPitch) -> Unit, onDelete: (() -> Unit)? = null) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var name by remember { mutableStateOf(initial.name) }
    var size by remember { mutableStateOf(initial.size) }
    var length by remember { mutableStateOf(initial.lengthM?.let(::formatMeters).orEmpty()) }
    var width by remember { mutableStateOf(initial.widthM?.let(::formatMeters).orEmpty()) }
    val error = SavedPitch.measuresError(length, width)
    val valid = name.isNotBlank() && error == null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(if (initial.id == 0) s.newPitchTitle else s.editPitch, color = c.textPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(SavedPitch.MAX_NAME) }, label = { Text(s.pitchName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilterChips(listOf("11" to s.football11, "7" to s.football7), size.toString()) { size = it.toInt() }
                Text(s.pitchMetersHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val typical = com.ppcoach.tracker.data.PitchTemplate.dimensions(size)
                    OutlinedTextField(
                        length, { length = it.take(6) }, label = { Text(s.pitchLengthM) }, placeholder = { Text(formatMeters(typical.first)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        width, { width = it.take(6) }, label = { Text(s.pitchWidthM) }, placeholder = { Text(formatMeters(typical.second)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                    )
                }
                error?.let { Text(s.pitchMeasuresError(it), style = MaterialTheme.typography.bodyMedium, color = c.negative) }
                if (onDelete != null) PpTextButton(s.deletePitch, onDelete, color = c.negative)
            }
        },
        confirmButton = {
            PrimaryButton(s.save, {
                val both = length.isNotBlank() && width.isNotBlank()
                onSave(initial.copy(
                    name = name.trim(), size = size,
                    lengthM = if (both) SavedPitch.parseMeters(length) else null,
                    widthM = if (both) SavedPitch.parseMeters(width) else null,
                ))
            }, enabled = valid)
        },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}

/** 60 → "60", 60.5 → "60.5". */
fun formatMeters(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)
