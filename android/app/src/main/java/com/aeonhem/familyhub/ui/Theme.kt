package com.aeonhem.familyhub.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * One colour theme. "Teal" is the approved mockup; the rest swap the same
 * slots, so every screen follows whichever is picked under the settings cog.
 * Same names and colours as web/static/app.css.
 */
enum class Palette(
    val label: String,
    val dark: Boolean,
    val teal: Color, // main colour: Today's dinner card, buttons, selected tab
    val tealSoft: Color, // text on the main colour, selected tab background
    val tealLine: Color,
    val orange: Color, // ticked chores
    val orangeSoft: Color,
    val orangeInk: Color,
    val ground: Color,
    val card: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val track: Color,
    val memoBg: Color,
    val memoInk: Color,
    val doneInk: Color,
    val tealText: Color = teal, // the main colour as text on cards; lighter on Night
) {
    TEAL(
        "Teal", false,
        Color(0xFF1F6F6B), Color(0xFFCFE6E3), Color(0xFFA9C9C6),
        Color(0xFFD9692A), Color(0xFFFBE3D3), Color(0xFF8A3E12),
        Color(0xFFF4F5F1), Color(0xFFFFFFFF), Color(0xFF1C2321), Color(0xFF5A6461),
        Color(0xFFA9B3AF), Color(0xFFE6E9E3), Color(0xFFFFF4EA), Color(0xFFB4531C), Color(0xFF7A8480),
    ),
    OCEAN(
        "Ocean", false,
        Color(0xFF1F5A86), Color(0xFFD3E3F0), Color(0xFFA8C2D8),
        Color(0xFFE0703A), Color(0xFFFCE4D6), Color(0xFF8A3E12),
        Color(0xFFF2F5F8), Color(0xFFFFFFFF), Color(0xFF1A2330), Color(0xFF56616E),
        Color(0xFFA7B2BE), Color(0xFFE3E8EE), Color(0xFFFFF4EA), Color(0xFFB4531C), Color(0xFF7A8490),
    ),
    BERRY(
        "Berry", false,
        Color(0xFF7A3B72), Color(0xFFF0DCEC), Color(0xFFCFAAC8),
        Color(0xFFD2553F), Color(0xFFFADDD6), Color(0xFF86301F),
        Color(0xFFF7F3F5), Color(0xFFFFFFFF), Color(0xFF261C24), Color(0xFF665A63),
        Color(0xFFB8AAB4), Color(0xFFEDE5EA), Color(0xFFFDF0F0), Color(0xFFA8402E), Color(0xFF85798A),
    ),
    FOREST(
        "Forest", false,
        Color(0xFF2E6337), Color(0xFFD5E8D3), Color(0xFFAAC8A9),
        Color(0xFFC07F12), Color(0xFFF6E6C4), Color(0xFF6E4A08),
        Color(0xFFF3F5EF), Color(0xFFFFFFFF), Color(0xFF1C231D), Color(0xFF5A645A),
        Color(0xFFAAB3A8), Color(0xFFE5E9E1), Color(0xFFFBF3E2), Color(0xFF8F5E0A), Color(0xFF7D867B),
    ),
    NIGHT(
        "Night", true,
        Color(0xFF2B7F79), Color(0xFFCDEAE6), Color(0xFF3F6E6A),
        Color(0xFFE07A3F), Color(0xFF4A2B1A), Color(0xFFF6C7A8),
        Color(0xFF121816), Color(0xFF1D2522), Color(0xFFE5ECE9), Color(0xFF9BA7A3),
        Color(0xFF4B5854), Color(0xFF2C3733), Color(0xFF33261C), Color(0xFFF0A574), Color(0xFF6F7B77),
        tealText = Color(0xFF6CC4BC),
    );

    companion object {
        fun named(name: String?) = entries.firstOrNull { it.name == name } ?: TEAL
    }
}

// The screens read these; they follow the picked palette.
object Hub {
    var palette by mutableStateOf(Palette.TEAL)

    val Teal get() = palette.teal
    val TealSoft get() = palette.tealSoft
    val TealLine get() = palette.tealLine
    val TealText get() = palette.tealText
    val Orange get() = palette.orange
    val OrangeSoft get() = palette.orangeSoft
    val OrangeInk get() = palette.orangeInk
    val Ground get() = palette.ground
    val Card get() = palette.card
    val Ink get() = palette.ink
    val Muted get() = palette.muted
    val Line get() = palette.line
    val Track get() = palette.track
    val MemoBg get() = palette.memoBg
    val MemoInk get() = palette.memoInk
    val DoneInk get() = palette.doneInk
}

@Composable
fun HubTheme(content: @Composable () -> Unit) {
    val p = Hub.palette
    val base = if (p.dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = p.teal,
            onPrimary = Color.White,
            secondary = p.orange,
            background = p.ground,
            surface = p.card,
            onBackground = p.ink,
            onSurface = p.ink,
            onSurfaceVariant = p.muted,
            outline = p.line,
            surfaceVariant = p.track,
            // Dialogs use these, so they match the theme too.
            surfaceContainerLowest = p.card,
            surfaceContainerLow = p.card,
            surfaceContainer = p.card,
            surfaceContainerHigh = p.card,
            surfaceContainerHighest = p.track,
        ),
        content = content,
    )
}
