package fi.kasvila.reko.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import fi.kasvila.reko.UiPrefs

/*
 * ── Kasvila appearance system ────────────────────────────────────────────────
 *
 * Four curated presets, each defined for BOTH a light and a dark variant. The
 * light/dark choice comes from [UiPrefs.mode]:
 *   system -> follow the phone (isSystemInDarkTheme)
 *   light  -> always the light variant
 *   dark   -> always the dark variant
 *
 * Every body-text pairing here measures >= 11:1 (WCAG AAA) so the app stays
 * readable in every combination. Backgrounds avoid pure white / pure black
 * except in the "kirkas" (outdoor) preset, where maximum contrast is the point.
 */

// Shared status colors (kept out of the accent so red/amber still read as red/amber).
private val ErrorDark = Color(0xFFD85A4A)
private val ErrorLight = Color(0xFFB3261E)

/**
 * Build a full Material 3 [ColorScheme] from a small set of Kasvila tokens.
 * Starts from the Material default (so every unset role is sensible) and maps our
 * palette onto the roles the app actually uses, including the surfaceContainer
 * family so menus, cards and sheets follow the theme instead of Material's
 * default purple-tinted neutrals.
 */
private fun scheme(
    dark: Boolean,
    bg: Long, surface: Long, surfaceVar: Long,
    text: Long, dim: Long,
    accent: Long, accentInk: Long, border: Long
): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val cBg = Color(bg); val cSurface = Color(surface); val cSurfaceVar = Color(surfaceVar)
    val cText = Color(text); val cDim = Color(dim)
    val cAccent = Color(accent); val cAccentInk = Color(accentInk); val cBorder = Color(border)
    return base.copy(
        primary = cAccent, onPrimary = cAccentInk,
        secondary = cAccent, onSecondary = cAccentInk,
        tertiary = cAccent, onTertiary = cAccentInk,
        primaryContainer = cSurfaceVar, onPrimaryContainer = cText,
        secondaryContainer = cSurfaceVar, onSecondaryContainer = cText,
        background = cBg, onBackground = cText,
        surface = cSurface, onSurface = cText,
        surfaceVariant = cSurfaceVar, onSurfaceVariant = cDim,
        surfaceTint = cAccent,
        surfaceContainerLowest = cBg,
        surfaceContainerLow = cSurface,
        surfaceContainer = cSurface,
        surfaceContainerHigh = cSurfaceVar,
        surfaceContainerHighest = cSurfaceVar,
        surfaceBright = cSurface,
        surfaceDim = cBg,
        outline = cBorder, outlineVariant = cBorder,
        error = if (dark) ErrorDark else ErrorLight,
        onError = Color(0xFFFFFFFF)
    )
}

// ── the four presets, light + dark ───────────────────────────────────────────

private val Kasvila_Light = scheme(false,
    bg = 0xFFF4F6EE, surface = 0xFFFFFFFF, surfaceVar = 0xFFECEFE1,
    text = 0xFF21261A, dim = 0xFF565C4A, accent = 0xFF4C6B14, accentInk = 0xFFFFFFFF, border = 0xFFE4E8D8)
private val Kasvila_Dark = scheme(true,
    bg = 0xFF1C1D1A, surface = 0xFF26271F, surfaceVar = 0xFF2E2F26,
    text = 0xFFECEEE3, dim = 0xFF9A9C8E, accent = 0xFFB0C42E, accentInk = 0xFF1C1D1A, border = 0xFF33352A)

private val Neutraali_Light = scheme(false,
    bg = 0xFFF3F4F5, surface = 0xFFFFFFFF, surfaceVar = 0xFFE9EBED,
    text = 0xFF1E2023, dim = 0xFF666A70, accent = 0xFF3C6E5C, accentInk = 0xFFFFFFFF, border = 0xFFE3E5E8)
private val Neutraali_Dark = scheme(true,
    bg = 0xFF17181A, surface = 0xFF212327, surfaceVar = 0xFF2A2C30,
    text = 0xFFE6E8EB, dim = 0xFF979BA1, accent = 0xFF86B3A3, accentInk = 0xFF10201B, border = 0xFF2C2F33)

private val Kirkas_Light = scheme(false,
    bg = 0xFFFFFFFF, surface = 0xFFFFFFFF, surfaceVar = 0xFFF0F1EC,
    text = 0xFF0B0C0A, dim = 0xFF35382F, accent = 0xFF2E5106, accentInk = 0xFFFFFFFF, border = 0xFFC4C9BA)
private val Kirkas_Dark = scheme(true,
    bg = 0xFF000000, surface = 0xFF0E0E0E, surfaceVar = 0xFF1A1A17,
    text = 0xFFFFFFFF, dim = 0xFFC7C9C2, accent = 0xFFC9E64F, accentInk = 0xFF000000, border = 0xFF333730)

private val Lammin_Light = scheme(false,
    bg = 0xFFF3EADA, surface = 0xFFFBF5E9, surfaceVar = 0xFFECE0CB,
    text = 0xFF2E2A20, dim = 0xFF6B6353, accent = 0xFF9A5B12, accentInk = 0xFFFFFFFF, border = 0xFFE6DAC3)
private val Lammin_Dark = scheme(true,
    bg = 0xFF1A1613, surface = 0xFF241E18, surfaceVar = 0xFF2C251D,
    text = 0xFFECE3D4, dim = 0xFFA2988A, accent = 0xFFE0A33A, accentInk = 0xFF1A1613, border = 0xFF352C22)

private fun colorSchemeFor(preset: String, dark: Boolean): ColorScheme = when (preset) {
    "neutraali" -> if (dark) Neutraali_Dark else Neutraali_Light
    "kirkas" -> if (dark) Kirkas_Dark else Kirkas_Light
    "lammin" -> if (dark) Lammin_Dark else Lammin_Light
    else -> if (dark) Kasvila_Dark else Kasvila_Light
}

/**
 * Font family for a preset id.
 *
 * Roboto is the Android system default, so it needs no asset. The other three are
 * resolved BY NAME at runtime from res/font, so the app compiles and runs even
 * before the .ttf files are added — it simply falls back to the system font until
 * they are present. Drop these files into app/src/main/res/font to enable them:
 *   inter.ttf, lexend.ttf, atkinson_hyperlegible.ttf
 * (all free from fonts.google.com; use the variable or regular weight).
 */
@Composable
private fun fontFamilyFor(id: String): FontFamily {
    if (id == "roboto") return FontFamily.Default
    val resName = when (id) {
        "inter" -> "inter"
        "lexend" -> "lexend"
        "atkinson" -> "atkinson_hyperlegible"
        else -> return FontFamily.Default
    }
    val ctx = LocalContext.current
    val resId = ctx.resources.getIdentifier(resName, "font", ctx.packageName)
    return if (resId != 0) FontFamily(Font(resId)) else FontFamily.Default
}

/** Apply the chosen font family to every text style in the default type scale. */
private fun typographyWith(f: FontFamily): Typography {
    val d = Typography()
    return Typography(
        displayLarge = d.displayLarge.copy(fontFamily = f),
        displayMedium = d.displayMedium.copy(fontFamily = f),
        displaySmall = d.displaySmall.copy(fontFamily = f),
        headlineLarge = d.headlineLarge.copy(fontFamily = f),
        headlineMedium = d.headlineMedium.copy(fontFamily = f),
        headlineSmall = d.headlineSmall.copy(fontFamily = f),
        titleLarge = d.titleLarge.copy(fontFamily = f),
        titleMedium = d.titleMedium.copy(fontFamily = f),
        titleSmall = d.titleSmall.copy(fontFamily = f),
        bodyLarge = d.bodyLarge.copy(fontFamily = f),
        bodyMedium = d.bodyMedium.copy(fontFamily = f),
        bodySmall = d.bodySmall.copy(fontFamily = f),
        labelLarge = d.labelLarge.copy(fontFamily = f),
        labelMedium = d.labelMedium.copy(fontFamily = f),
        labelSmall = d.labelSmall.copy(fontFamily = f)
    )
}

@Composable
fun KasvilaTheme(prefs: UiPrefs = UiPrefs(), content: @Composable () -> Unit) {
    val dark = when (prefs.mode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val colors = colorSchemeFor(prefs.preset, dark)
    val typography = typographyWith(fontFamilyFor(prefs.font))

    // Scale ALL sp-based text uniformly by folding the chosen factor into the
    // density's fontScale. This layers on top of (does not replace) the phone's
    // own accessibility font-size setting, so both are respected.
    val density = LocalDensity.current
    val scaledDensity = Density(density.density, density.fontScale * prefs.textScale)

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
