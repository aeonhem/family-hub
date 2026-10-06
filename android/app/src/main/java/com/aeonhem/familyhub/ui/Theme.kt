package com.aeonhem.familyhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colours from the approved mockup.
object Hub {
    val Teal = Color(0xFF1F6F6B)
    val TealSoft = Color(0xFFCFE6E3)
    val Orange = Color(0xFFD9692A)
    val OrangeSoft = Color(0xFFFBE3D3)
    val Ground = Color(0xFFF4F5F1)
    val Card = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1C2321)
    val Muted = Color(0xFF5A6461)
    val Line = Color(0xFFA9B3AF)
    val Track = Color(0xFFE6E9E3)
    val MemoBg = Color(0xFFFFF4EA)
    val MemoInk = Color(0xFFB4531C)
}

@Composable
fun HubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Hub.Teal,
            onPrimary = Color.White,
            secondary = Hub.Orange,
            background = Hub.Ground,
            surface = Hub.Card,
            onBackground = Hub.Ink,
            onSurface = Hub.Ink,
        ),
        content = content,
    )
}
