package org.olcbox.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun IosSocksOnboardingDialog(
    settings: ApplicationSocksProxySettings,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Подключение приложений") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "На iPhone приложение работает как локальный SOCKS5-прокси. Укажите эти настройки " +
                        "в клиенте с поддержкой SOCKS5 (например, Karing или Shadowrocket) и запустите Мурка VPN."
                )
                SelectionContainer {
                    Text(
                        text = socksSettingsText(settings),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Понятно")
            }
        },
        dismissButton = {
            TextButton(onClick = onCopy) {
                Text("Скопировать настройки")
            }
        }
    )
}

fun socksSettingsText(settings: ApplicationSocksProxySettings): String = buildString {
    appendLine("Type: SOCKS5")
    appendLine("Сервер: ${settings.host}")
    appendLine("Порт: ${settings.port}")
    appendLine("Логин: ${settings.username}")
    append("Пароль: ${settings.password}")
}
