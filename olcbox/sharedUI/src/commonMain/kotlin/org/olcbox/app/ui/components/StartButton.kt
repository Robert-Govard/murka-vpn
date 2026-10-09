package org.olcbox.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.ui.theme.MurkaCyan
import org.olcbox.app.ui.theme.MurkaRimColors
import org.olcbox.app.ui.theme.MurkaViolet

sealed class StartButtonState {
    object Idle : StartButtonState()
    object Loading : StartButtonState()
    object Success : StartButtonState()
}

/** Power button in the style of the logo: a cyan → violet neon rim that lights up when connected. */
@Composable
fun StartButton(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    isLoading: Boolean,
    requiresSetup: Boolean = false,
    label: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val glow by animateFloatAsState(if (isActive) 1f else 0f, label = "glow")
    val rimAlpha by animateFloatAsState(if (isActive || isLoading) 1f else 0.55f, label = "rim")

    val contentColor = if (isActive) Color.White else MaterialTheme.colorScheme.primary
    val rim = Brush.sweepGradient(MurkaRimColors)

    Box(
        modifier = modifier
            .size(232.dp)
            .drawBehind {
                if (glow > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(MurkaCyan.copy(alpha = 0.45f * glow), Color.Transparent),
                            radius = size.minDimension / 2
                        )
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(200.dp)
                .alpha(rimAlpha)
                .background(rim, CircleShape)
        )
        Box(
            modifier = Modifier
                .size(200.dp)
                .padding(6.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .drawBehind {
                    if (glow > 0f) {
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(MurkaCyan, MurkaViolet),
                                start = Offset(0f, 0f),
                                end = Offset(size.width, size.height)
                            ),
                            alpha = glow
                        )
                    }
                }
                .clickable(enabled = enabled) {
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(176.dp),
                    color = contentColor,
                    strokeWidth = 4.dp
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "Кнопка запуска",
                    tint = contentColor.copy(alpha = if (isLoading || !enabled) 0.5f else 1f),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = label ?: when {
                        isLoading -> "СТОП"
                        isActive -> "СТОП"
                        requiresSetup -> "НАСТРОИТЬ"
                        else -> "СТАРТ"
                    },
                    color = contentColor.copy(alpha = if (!enabled) 0.7f else 1f),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
