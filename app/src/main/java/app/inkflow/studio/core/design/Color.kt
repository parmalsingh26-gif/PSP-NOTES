package app.inkflow.studio.core.design

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val InkPrimary = Color(0xFF2563EB)
val InkPrimaryDark = Color(0xFF1D4ED8)
val InkAccent = Color(0xFF0D9488)
val InkAccentDark = Color(0xFF14B8A6)

val InkCanvasLight = Color(0xFFF8FAFC)
val InkCanvasDark = Color(0xFF0F172A)

val InkSurfaceLight = Color(0xFFFFFFFF)
val InkSurfaceDark = Color(0xFF1E293B)

val InkBorderLight = Color(0xFFE2E8F0)
val InkBorderDark = Color(0xFF334155)

val InkTextPrimaryLight = Color(0xFF0F172A)
val InkTextPrimaryDark = Color(0xFFF8FAFC)

val InkTextSecondaryLight = Color(0xFF64748B)
val InkTextSecondaryDark = Color(0xFF94A3B8)

val LightColorScheme = lightColorScheme(
    primary = InkPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = InkAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    background = InkCanvasLight,
    onBackground = InkTextPrimaryLight,
    surface = InkSurfaceLight,
    onSurface = InkTextPrimaryLight,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = InkTextSecondaryLight,
    outline = InkBorderLight
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF1E3A8A),
    primaryContainer = Color(0xFF1E40AF),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = InkAccentDark,
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    background = InkCanvasDark,
    onBackground = InkTextPrimaryDark,
    surface = InkSurfaceDark,
    onSurface = InkTextPrimaryDark,
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = InkTextSecondaryDark,
    outline = InkBorderDark
)

