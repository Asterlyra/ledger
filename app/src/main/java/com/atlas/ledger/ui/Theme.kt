package com.atlas.ledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.sp

val Cobalt = Color(0xFF3453F4)
val HeroBlue = Color(0xFF3052E8)
val HeroViolet = Color(0xFF6834D6)
val SubscriptionFill = Color(0xFF793BE7)
val DurableFill = Color(0xFFFFB21B)
val DurableInk = Color(0xFF492600)

enum class LedgerAccent(val light: Color, val dark: Color) {
    Primary(Cobalt, Color(0xFFA9BAFF)),
    Income(Color(0xFF00764D), Color(0xFF55E5A5)),
    Expense(Color(0xFFC52643), Color(0xFFFF8EA5)),
    Subscription(Color(0xFF7330D9), Color(0xFFC4A1FF)),
    Durable(Color(0xFFAC4200), Color(0xFFFFBD62)),
    Transfer(Color(0xFF006F8F), Color(0xFF68DDF5)),
}

private val LocalDarkPalette = staticCompositionLocalOf { false }
private val CompactTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontSize = 28.sp, lineHeight = 34.sp),
        headlineMedium = base.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = base.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}
@Composable fun accentColor(accent: LedgerAccent): Color = if (LocalDarkPalette.current) accent.dark else accent.light
@Composable fun accentContainer(accent: LedgerAccent): Color = accentColor(accent).copy(alpha = .13f).compositeOver(MaterialTheme.colorScheme.surface)

fun categoryAccent(category: String): LedgerAccent = when (category) {
    "订阅", "学习", "娱乐" -> LedgerAccent.Subscription
    "耐用品", "餐饮", "居住" -> LedgerAccent.Durable
    "交通" -> LedgerAccent.Transfer
    "医疗" -> LedgerAccent.Income
    "购物" -> LedgerAccent.Expense
    else -> LedgerAccent.Primary
}

@Composable fun LedgerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) darkColorScheme(
        primary = Color(0xFFA9BAFF), onPrimary = Color(0xFF142269),
        secondary = Color(0xFFC4A1FF), onSecondary = Color(0xFF35126A),
        tertiary = Color(0xFFFFBD62), onTertiary = DurableInk,
        background = Color(0xFF101426), onBackground = Color(0xFFEDF0FF),
        surface = Color(0xFF1A2038), onSurface = Color(0xFFEDF0FF),
        surfaceVariant = Color(0xFF29324B), onSurfaceVariant = Color(0xFFBBC5E0),
        primaryContainer = Color(0xFF293982), onPrimaryContainer = Color(0xFFE6EAFF),
        secondaryContainer = Color(0xFF442674), onSecondaryContainer = Color(0xFFF0E4FF),
        error = Color(0xFFFF8EA5),
    ) else lightColorScheme(
        primary = Cobalt, onPrimary = Color.White,
        secondary = LedgerAccent.Subscription.light, onSecondary = Color.White,
        tertiary = LedgerAccent.Durable.light, onTertiary = Color.White,
        background = Color(0xFFF4F6FF), onBackground = Color(0xFF17213D),
        surface = Color.White, onSurface = Color(0xFF17213D),
        surfaceVariant = Color(0xFFE8EDFF), onSurfaceVariant = Color(0xFF59647D),
        primaryContainer = Color(0xFFE3E9FF), onPrimaryContainer = Color(0xFF21389F),
        secondaryContainer = Color(0xFFEEE3FF), onSecondaryContainer = Color(0xFF51219A),
        error = LedgerAccent.Expense.light,
    )
    CompositionLocalProvider(LocalDarkPalette provides darkTheme) { MaterialTheme(colorScheme = colors, typography = CompactTypography, content = content) }
}
