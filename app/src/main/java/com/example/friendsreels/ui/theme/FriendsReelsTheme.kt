package com.example.friendsreels.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Instagram-inspired dark theme for Friends Reels (introduced s50 after
 * the user's feedback: *"a nivel de design e cores está super longe do
 * insta, mesmo que não consigas meter igual tenta melhorar"*).
 *
 * Palette drawn from Instagram's brand guide:
 * - **Pure black surface** (`#000000`) matches the native Reels player
 *   and the DM inbox.
 * - **Elevated dark grey** (`#121212`, `#1F1F1F`, `#262626`) for cards
 *   and inputs.
 * - **Neutral grey text** (`#A8A8A8`) for secondary content.
 * - **Instagram gradient accent** (yellow → orange → pink → purple →
 *   blue) exposed via [InstagramGradient] for hero buttons and
 *   highlight bars.
 * - **Pink primary** (`#E1306C`) for single-color CTAs / active chips.
 *
 * Typography favours SemiBold for titles (matches IG headers) and
 * normal weight for body text; `letterSpacing` is slightly tightened
 * to feel closer to the SF Pro / Instagram Sans metrics we can't ship
 * as fonts.
 */
private val InstagramBlack = Color(0xFF000000)
private val InstagramSurface = Color(0xFF121212)
private val InstagramSurfaceVariant = Color(0xFF1F1F1F)
private val InstagramSurfaceElevated = Color(0xFF262626)
private val InstagramOutline = Color(0xFF3A3A3A)
private val InstagramSecondaryText = Color(0xFFA8A8A8)
private val InstagramSecondaryTextDim = Color(0xFF737373)
private val InstagramPink = Color(0xFFE1306C)
private val InstagramPinkDeep = Color(0xFFC13584)
private val InstagramPurple = Color(0xFF833AB4)
private val InstagramBlue = Color(0xFF405DE6)
private val InstagramYellow = Color(0xFFFCAF45)
private val InstagramOrange = Color(0xFFF77737)
private val InstagramRed = Color(0xFFFD1D1D)

/** Instagram signature diagonal gradient. Use with `Brush.linearGradient`. */
val InstagramGradient: List<Color> = listOf(
    InstagramYellow,
    InstagramOrange,
    InstagramPink,
    InstagramPurple,
    InstagramBlue,
)

/** Softer gradient stops used for highlight bars and story rings. */
val InstagramGradientSoft: List<Color> = listOf(
    Color(0xFFF9CE34),
    Color(0xFFEE2A7B),
    Color(0xFF6228D7),
)

private val InstagramColorScheme = darkColorScheme(
    primary = InstagramPink,
    onPrimary = Color.White,
    primaryContainer = InstagramPinkDeep,
    onPrimaryContainer = Color.White,
    secondary = InstagramBlue,
    onSecondary = Color.White,
    tertiary = InstagramPurple,
    onTertiary = Color.White,
    background = InstagramBlack,
    onBackground = Color.White,
    surface = InstagramBlack,
    onSurface = Color.White,
    surfaceVariant = InstagramSurfaceVariant,
    onSurfaceVariant = InstagramSecondaryText,
    surfaceContainer = InstagramSurface,
    surfaceContainerHigh = InstagramSurfaceElevated,
    surfaceContainerHighest = InstagramSurfaceElevated,
    outline = InstagramOutline,
    outlineVariant = InstagramSecondaryTextDim,
    error = InstagramRed,
    onError = Color.White,
)

private val InstagramTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
    ),
)

@Composable
fun FriendsReelsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InstagramColorScheme,
        typography = InstagramTypography,
        content = content,
    )
}
