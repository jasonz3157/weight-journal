package com.jasonz3157.weightjournal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
internal fun SettingsScreen(padding: PaddingValues, state: JournalUiState, model: JournalViewModel) {
    val focus = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
        contentPadding = padding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(Modifier.padding(horizontal = 16.dp).padding(top = FirstCardTopPadding)) {
                Panel {
                    val saved = state.journal.height
                    var draft by rememberSaveable { mutableStateOf<String?>(null) }
                    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
                    var failed by rememberSaveable { mutableStateOf(false) }
                    val input = draft ?: saved?.let { formatNumber(it, "0.###############") }.orEmpty()
                    fun update(value: Double?, success: String) {
                        val error = model.setValue(HEIGHT_KEY, value)
                        failed = error != null
                        feedback = error ?: success
                        if (error == null) {
                            draft = null
                            focus.clearFocus()
                        }
                    }
                    val submit = {
                        val number = input.trim().replace(',', '.').toDoubleOrNull()
                        if (number == null || number !in 1.0..300.0) {
                            feedback = "请输入 1–300 cm 的身高。"
                            failed = true
                        } else {
                            update(number, "身高已保存")
                        }
                    }
                    SectionTitle("身高")
                    SupportingText("身高用于计算全部记录的 BMI。")
                    DecimalField(input, "身高", "cm", { draft = it; feedback = null }, submit, feedback?.takeIf { failed })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = submit, enabled = state.storageError == null) { Text("保存身高") }
                        if (saved != null) {
                            TextButton(onClick = { update(null, "身高已清除") }, enabled = state.storageError == null) {
                                Text("清除身高")
                            }
                        }
                    }
                    feedback?.takeUnless { failed }?.let { SupportingText(it) }
                    state.storageError?.let { SupportingText(it, error = true) }
                }
            }
        }
        item {
            Box(Modifier.padding(horizontal = 16.dp)) { WebDavPanel(state, model) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun WebDavPanel(state: JournalUiState, model: JournalViewModel) {
    val focus = LocalFocusManager.current
    val current = state.dav
    var editing by remember(current) { mutableStateOf(current == null) }
    var address by remember(current) { mutableStateOf(current?.address?.toString().orEmpty()) }
    var username by remember(current) { mutableStateOf(current?.username.orEmpty()) }
    val password = remember(current) { TextFieldState() }
    var error by remember(current) { mutableStateOf<String?>(null) }
    val usesNutstore = if (editing) {
        address.trim().toHttpUrlOrNull()?.let { it.isHttps && it.host == "dav.jianguoyun.com" && it.port == 443 } == true
    } else current?.isNutstore == true
    Panel {
        SectionTitle("WebDAV 同步")
        SupportingText(state.syncStatus, error = state.syncError, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (current != null && !editing) {
            SupportingText(current.address.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = model::syncManually, enabled = state.canSync && !state.manuallySyncing) { Text("立即同步") }
                OutlinedButton(onClick = { editing = true }, enabled = !state.syncBusy) { Text("编辑配置") }
            }
        } else {
            OutlinedTextField(
                value = address, onValueChange = { address = it; error = null }, modifier = Modifier.fillMaxWidth(),
                label = { Text("WebDAV 地址") }, singleLine = true,
                placeholder = { Text("https://dav.example.com/") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = { Text("填写已有目录或 JSON 文件地址。目录默认使用 weight-journal.json。") },
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it; error = null }, modifier = Modifier.fillMaxWidth(),
                label = { Text("用户名") }, singleLine = true,
            )
            OutlinedSecureTextField(
                state = password, modifier = Modifier.fillMaxWidth(), label = { Text("密码") },
                placeholder = if (current != null) { { Text("留空可沿用已保存的密码") } } else null,
            )
            if (usesNutstore) SupportingText("请使用坚果云第三方应用密码。")
            error?.let { SupportingText(it, error = true) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Button(enabled = !state.syncBusy && state.storageError == null, onClick = {
                    try {
                        val existingPassword = current?.password?.takeIf { current.address.host == address.trim().toHttpUrlOrNull()?.host &&
                            current.address.port == address.trim().toHttpUrlOrNull()?.port && current.username == username }
                        val next = DavSettings.validate(address, username, password.text.toString().ifEmpty { existingPassword.orEmpty() })
                        if (next == current) {
                            model.syncManually()
                            editing = false
                        } else {
                            model.configure(next)
                        }
                        error = null
                        focus.clearFocus()
                    } catch (problem: IllegalArgumentException) {
                        error = problem.message
                    }
                }) { Text("保存并同步") }
                if (current != null) {
                    TextButton(
                        onClick = {
                            focus.clearFocus()
                            model.configure(null)
                        },
                        enabled = !state.syncBusy,
                    ) {
                        Text("断开同步", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        editing = false
                        address = current.address.toString()
                        username = current.username
                        password.edit { replace(0, length, "") }
                        error = null
                    }) { Text("取消") }
                }
            }
        }
    }
}
