package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.telemetry.Telemetry

/** Provided by the platform entry point; null where telemetry is not wired. */
val LocalTelemetry = staticCompositionLocalOf<Telemetry?> { null }

/** Statistics toggle and «Сообщить о проблеме» for the log screen. */
@Composable
fun TelemetryControls(modifier: Modifier = Modifier) {
    val telemetry = LocalTelemetry.current ?: return
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(telemetry.enabled) }
    var dialogOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Отправлять статистику и отчёты об ошибках",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                telemetry.enabled = it
            })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { result = null; dialogOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Сообщить о проблеме")
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { if (!sending) dialogOpen = false },
            title = { Text("Сообщить о проблеме") },
            text = {
                Column {
                    Text(
                        "Опишите, что случилось. К сообщению приложится журнал без ссылок, ключей и паролей.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(2000) },
                        placeholder = { Text("Например: не грузится Telegram на сервере Германия") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    result?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                    sending = true
                    scope.launch {
                        val ok = telemetry.reportUser(text.trim())
                        sending = false
                        if (ok) {
                            text = ""
                            result = "Отправлено, спасибо!"
                        } else {
                            result = "Не удалось отправить. Проверьте интернет и что добавлена подписка Мурки."
                        }
                    }
                }) { Text(if (sending) "Отправка…" else "Отправить") }
            },
            dismissButton = {
                TextButton(enabled = !sending, onClick = { dialogOpen = false }) { Text("Закрыть") }
            }
        )
    }
}
