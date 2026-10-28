package com.ppcoach.tracker.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.Consent
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme

/** The four boxes before the first "Send for analysis" (docs/legal/). "Accept and send" only with all four. */
@Composable
fun ConsentDialog(vm: CoachViewModel, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val context = LocalContext.current
    val checks = remember { mutableStateListOf(false, false, false, false) }
    val en = s.code == "en"
    fun open(doc: Consent.Doc) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Consent.url(vm.settings.serverUrl, doc, en)))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(s.consentTitle, color = c.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(s.consentIntro, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                listOf(s.consentRights, s.consentPeople, s.consentAi, s.consentRead).forEachIndexed { i, text ->
                    Row(Modifier.fillMaxWidth().clickable { checks[i] = !checks[i] }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Checkbox(checks[i], { checks[i] = it }, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Row {
                    PpTextButton(s.privacyNotice, { open(Consent.Doc.PRIVACY) })
                    PpTextButton(s.fullTerms, { open(Consent.Doc.TERMS) })
                }
                Text(s.consentPromise, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }
        },
        confirmButton = { PrimaryButton(s.acceptAndSend, { vm.acceptConsent() }, enabled = checks.all { it }) },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}
