package org.olcbox.app.ui.features.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import multiplatform_app.sharedui.generated.resources.Res
import multiplatform_app.sharedui.generated.resources.murka_logo
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenAppBar(
    onHistoryClick: () -> Unit = {},
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    showSplitTunnelingButton: Boolean = false,
    onSplitTunnelingClick: () -> Unit = {},
    onAddClick: () -> Unit = {}
) {
    CenterAlignedTopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(Res.drawable.murka_logo),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Мурка VPN",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "VPN и аварийный режим",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            if (showAppSettingsButton) {
                IconButton(onClick = onAppSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Настройки приложения",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                IconButton(onClick = onHistoryClick) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "Журнал",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        actions = {
            if (showSplitTunnelingButton) {
                IconButton(onClick = onSplitTunnelingClick) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = "Раздельное туннелирование",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onAddClick) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "Добавить подключение",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    )
}
