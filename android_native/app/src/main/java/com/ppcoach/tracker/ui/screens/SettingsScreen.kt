package com.ppcoach.tracker.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ppcoach.tracker.data.ChunkProfile
import com.ppcoach.tracker.data.ProxyResolution
import com.ppcoach.tracker.data.Consent
import com.ppcoach.tracker.data.StorageKind
import com.ppcoach.tracker.data.ThemeMode
import com.ppcoach.tracker.data.SetupQr
import com.ppcoach.tracker.ui.CoachViewModel
import com.ppcoach.tracker.ui.Screen
import com.ppcoach.tracker.ui.components.FilterChips
import com.ppcoach.tracker.ui.components.KeyValue
import com.ppcoach.tracker.ui.components.PpCard
import com.ppcoach.tracker.ui.components.PpTextButton
import com.ppcoach.tracker.ui.components.PpTopBar
import com.ppcoach.tracker.ui.components.PrimaryButton
import com.ppcoach.tracker.ui.components.SecondaryButton
import com.ppcoach.tracker.ui.components.SectionTitle
import com.ppcoach.tracker.ui.i18n.LocalStrings
import com.ppcoach.tracker.ui.theme.PpTheme
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun SettingsScreen(vm: CoachViewModel) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    val context = LocalContext.current
    val st = vm.settings
    val lib by vm.library.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        PpTopBar(s.navSettings)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PpCard {
                SectionTitle(s.language)
                FilterChips(listOf("es" to "Español", "en" to "English"), st.language.ifEmpty { Locale.getDefault().language.takeIf { it == "en" } ?: "es" }) { code ->
                    vm.updateSettings { it.copy(language = code) }
                }
                Spacer(Modifier.height(8.dp))
                SectionTitle(s.appearance)
                FilterChips(listOf(ThemeMode.SYSTEM.key to s.themeSystem, ThemeMode.LIGHT.key to s.themeLight, ThemeMode.DARK.key to s.themeDark), st.theme.key) { k ->
                    vm.updateSettings { it.copy(theme = ThemeMode.fromKey(k)) }
                }
            }

            // ── analysis system ──
            PpCard {
                SectionTitle(s.analysisSystem)
                var server by remember { mutableStateOf(st.serverUrl) }
                var email by remember { mutableStateOf(st.sessionEmail) }
                var password by remember { mutableStateOf("") }
                var status by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                SecondaryButton(s.scanSetupQr, { vm.push(Screen.ScanQr) }, Modifier.fillMaxWidth().padding(bottom = 8.dp), icon = Icons.Rounded.QrCodeScanner)
                if (st.loggedIn) {
                    Text(s.loggedInAs(st.sessionName.ifBlank { st.sessionEmail }), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    Text(st.serverUrl, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        SecondaryButton(s.testConnection, {
                            scope.launch { status = if (vm.testConnection(st.serverUrl)) s.connectionOk else s.connectionFailed }
                        })
                        SecondaryButton(s.logout, { vm.logout(); password = "" })
                    }
                } else {
                    OutlinedTextField(server, { server = it.trim() }, label = { Text(s.serverUrl) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(email, { email = it.trim() }, label = { Text(s.email) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text(s.password) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        PrimaryButton(s.login, {
                            busy = true
                            scope.launch {
                                status = vm.login(server, email, password)
                                if (status == null) password = ""
                                busy = false
                            }
                        }, enabled = !busy && server.isNotBlank() && email.isNotBlank() && password.isNotBlank())
                        SecondaryButton(s.testConnection, {
                            scope.launch { status = if (vm.testConnection(server)) s.connectionOk else s.connectionFailed }
                        }, enabled = server.isNotBlank())
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it == s.connectionOk) c.positive else c.negative, modifier = Modifier.padding(top = 6.dp)) }
                vm.pendingSetup?.let { qr ->
                    SetupQrDialog(qr, busy, onDismiss = { vm.pendingSetup = null }) { pw ->
                        busy = true
                        scope.launch {
                            // A new setup replaces the session there was.
                            if (st.loggedIn) vm.logout()
                            status = vm.login(qr.server, qr.email, pw)
                            if (status == null) {
                                server = qr.server
                                email = qr.email
                            }
                            vm.pendingSetup = null
                            busy = false
                        }
                    }
                }
            }

            // ── privacy and consent ──
            PpCard {
                SectionTitle(s.privacyAndConsent)
                if (st.consentVersion.isNotEmpty()) {
                    val local = runCatching {
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale(s.code)).format(Instant.parse(st.consentAt).atZone(ZoneId.systemDefault()))
                    }.getOrDefault(st.consentAt)
                    Text(s.consentAccepted(st.consentVersion, local), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    PpTextButton(s.withdrawConsent, { vm.withdrawConsent(); vm.toast(s.consentWithdrawn) }, color = c.negative)
                } else {
                    Text(s.consentNone, style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                }
                val en = s.code == "en"
                PpTextButton(s.privacyNotice, { open(context, Consent.url(st.serverUrl, Consent.Doc.PRIVACY, en)) })
                PpTextButton(s.fullTerms, { open(context, Consent.url(st.serverUrl, Consent.Doc.TERMS, en)) })
                PpTextButton(s.playersForm, { open(context, Consent.url(st.serverUrl, Consent.Doc.PLAYERS_FORM, en)) })
            }

            // ── storage ──
            PpCard {
                SectionTitle(s.storage)
                val storage = vm.storage
                for (kind in storage.available()) {
                    val root = storage.root(kind) ?: continue
                    val free = runCatching { StatFs(root.absolutePath).availableBytes / 1e9 }.getOrDefault(0.0)
                    val count = lib.clips.count { it.storage == kind }
                    val label = when (kind) {
                        StorageKind.PHONE -> s.storagePhone
                        StorageKind.PRIVATE -> s.storagePrivate
                        StorageKind.SD -> s.storageSd
                    }
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                                Text(s.freeSpace("%.1f".format(free), count), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                            Switch(
                                checked = st.storage == kind,
                                onCheckedChange = { if (it) vm.updateSettings { s2 -> s2.copy(storage = kind) } },
                                colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                            )
                        }
                        val path = storage.moviesDir(kind)?.absolutePath ?: ""
                        Text(path, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Row {
                            PpTextButton(s.copyPath, {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", path))
                                vm.toast(s.pathCopied)
                            })
                            val elsewhere = lib.clips.count { it.storage != kind }
                            if (st.storage == kind && elsewhere > 0) PpTextButton(s.moveHere(elsewhere), { vm.toast(s.moved(vm.moveAllTo(kind))) })
                        }
                    }
                }
                Text(s.recordingFormat, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(s.recordingFormatValue, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            }

            // ── technical ──
            PpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.showTechnical, style = MaterialTheme.typography.titleMedium, color = c.textPrimary, modifier = Modifier.weight(1f))
                    Switch(st.technical, { v -> vm.updateSettings { it.copy(technical = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = c.accent))
                }
                Text(s.analysisResolution, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    FilterChips(ProxyResolution.ALL.map { it to s.resolutionLabel(it) }, st.resolution) { r -> vm.updateSettings { it.copy(resolution = r) } }
                }
                Text(s.resolutionHelp(st.resolution), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                if (st.technical) {
                    SectionTitle(s.chunkProfile)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChips(listOf(ChunkProfile.DEV.name to s.profileDev, ChunkProfile.DEMO.name to s.profileDemo), st.chunkProfile) { name ->
                            vm.updateSettings { it.copy(chunkProfile = name, overlapMs = ChunkProfile.of(name).overlapMs) }
                        }
                    }
                    Text(s.overlapLabel(st.overlapMs / 1000), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    Slider(
                        value = (st.overlapMs / 1000).toFloat(),
                        onValueChange = { v -> vm.updateSettings { it.copy(overlapMs = Math.round(v) * 1000) } },
                        valueRange = 2f..5f,
                        steps = 2,
                    )
                    val cache = context.cacheDir.resolve("proxies").walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    Text("${s.tempCopies}: ${formatMb(cache)} MB", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                    Text("pp-coach-tracker ${vm.appVersion}", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                }
            }
        }
    }
}

/** What the setup QR brings, to confirm before logging in; the password is typed when the QR has none. */
@Composable
private fun SetupQrDialog(qr: SetupQr, busy: Boolean, onDismiss: () -> Unit, onLogin: (String) -> Unit) {
    val s = LocalStrings.current
    val c = PpTheme.colors
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(s.setupQrTitle, color = c.textPrimary) },
        text = {
            Column {
                KeyValue(s.serverUrl, qr.server)
                KeyValue(s.email, qr.email)
                if (qr.password.isNotEmpty()) {
                    KeyValue(s.password, "••••••••")
                } else {
                    OutlinedTextField(password, { password = it }, label = { Text(s.password) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                Text(s.setupQrHelp, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            PrimaryButton(s.login, { onLogin(qr.password.ifEmpty { password }) }, enabled = !busy && (qr.password.isNotEmpty() || password.isNotBlank()))
        },
        dismissButton = { PpTextButton(s.cancel, onDismiss) },
    )
}

private fun open(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
