package app.svyaz.messenger.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GROUP_RADIUS = 26.dp
private val BUBBLE_RADIUS = 20.dp
private val BUBBLE_JOINED_RADIUS = 6.dp

/** What the two styles disagree on beyond the Material colour scheme. */
@Immutable
class BrandTokens(
    val oneUi: Boolean,
    val groupColor: Color,
    val rowColor: Color,
    val groupLabel: Color,
    val headerScrolled: Color,
    val headerExpanded: Dp,
    val chatBar: Color,
    val field: Color,
    val bubbleIn: Color,
    val onBubbleIn: Color,
    val bubbleOut: Color,
    val onBubbleOut: Color,
    val unread: Color,
    val onUnread: Color,
    val online: Color,
    val avatarShape: Shape,
    val sendShape: Shape,
) {
    /** One UI joins neighbouring rows into one card; Material You leaves them on the background. */
    fun listItem(index: Int, count: Int): Modifier {
        if (!oneUi) return Modifier
        val top = if (index == 0) GROUP_RADIUS else 0.dp
        val bottom = if (index == count - 1) GROUP_RADIUS else 0.dp
        return Modifier
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(top, top, bottom, bottom))
            .background(groupColor)
    }

    fun bubbleShape(outgoing: Boolean, joinedAbove: Boolean, joinedBelow: Boolean): Shape {
        if (oneUi) return RoundedCornerShape(BUBBLE_RADIUS)
        val top = if (joinedAbove) BUBBLE_JOINED_RADIUS else BUBBLE_RADIUS
        val bottom = if (joinedBelow) BUBBLE_JOINED_RADIUS else BUBBLE_RADIUS
        return if (outgoing) {
            RoundedCornerShape(BUBBLE_RADIUS, top, bottom, BUBBLE_RADIUS)
        } else {
            RoundedCornerShape(top, BUBBLE_RADIUS, BUBBLE_RADIUS, bottom)
        }
    }
}

val GroupShape: Shape = RoundedCornerShape(GROUP_RADIUS)

val LocalBrand = staticCompositionLocalOf<BrandTokens> { error("AppTheme is not applied") }

private fun oneUiTokens(c: ColorScheme, dark: Boolean) = BrandTokens(
    oneUi = true,
    groupColor = c.surfaceContainer,
    rowColor = c.surfaceContainer,
    groupLabel = c.onSurfaceVariant,
    headerScrolled = c.background,
    headerExpanded = 208.dp,
    chatBar = c.background,
    field = c.surfaceContainer,
    bubbleIn = if (dark) Color(0xFF212625) else c.surfaceContainer,
    onBubbleIn = c.onSurface,
    bubbleOut = if (dark) Color(0xFF16493D) else Color(0xFFCDEBDF),
    onBubbleOut = if (dark) Color(0xFFDFF5ED) else Color(0xFF10312A),
    unread = Amber,
    onUnread = OnAmber,
    online = if (dark) OnlineDark else OnlineLight,
    avatarShape = CircleShape,
    sendShape = CircleShape,
)

private fun materialYouTokens(c: ColorScheme, dark: Boolean, dynamic: Boolean) = BrandTokens(
    oneUi = false,
    groupColor = Color.Transparent,
    rowColor = c.background,
    groupLabel = c.primary,
    headerScrolled = c.surfaceContainer,
    headerExpanded = 168.dp,
    chatBar = c.surfaceContainer,
    field = c.surfaceContainerHigh,
    bubbleIn = c.surfaceContainerHigh,
    onBubbleIn = c.onSurface,
    bubbleOut = c.primaryContainer,
    onBubbleOut = c.onPrimaryContainer,
    unread = if (dynamic) c.tertiary else Amber,
    onUnread = if (dynamic) c.onTertiary else OnAmber,
    online = if (dark) OnlineDark else OnlineLight,
    avatarShape = RoundedCornerShape(38),
    sendShape = RoundedCornerShape(16.dp),
)

private fun typography(oneUi: Boolean): Typography {
    val titleWeight = if (oneUi) FontWeight.SemiBold else FontWeight.Medium
    val base = Typography()
    fun TextStyle.system() = copy(fontFamily = FontFamily.Default)
    val screenTitle = if (oneUi) {
        TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    } else {
        TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
    }
    return base.copy(
        headlineLarge = screenTitle.system(),
        headlineMedium = screenTitle.system(),
        headlineSmall = base.headlineSmall.system(),
        titleLarge = base.titleLarge.copy(fontWeight = titleWeight).system(),
        titleMedium = base.titleMedium.copy(fontWeight = titleWeight).system(),
        titleSmall = base.titleSmall.system(),
        bodyLarge = base.bodyLarge.system(),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp).system(),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).system(),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium).system(),
        labelMedium = base.labelMedium.system(),
        labelSmall = base.labelSmall.system(),
    )
}

private fun shapes(oneUi: Boolean) = Shapes(
    extraSmall = RoundedCornerShape(if (oneUi) 14.dp else 10.dp),
    extraLarge = RoundedCornerShape(if (oneUi) GROUP_RADIUS else 28.dp),
)

@Composable
fun AppTheme(style: BrandStyle, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val oneUi = remember(style) { style.resolve(Build.MANUFACTURER) == BrandStyle.ONE_UI }
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = remember(oneUi, dark, context) {
        when {
            oneUi -> if (dark) OneUiDark else OneUiLight
            dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            else -> if (dark) BrandDark else BrandLight
        }
    }
    val tokens = remember(colors, oneUi) {
        if (oneUi) oneUiTokens(colors, dark) else materialYouTokens(colors, dark, dynamic)
    }
    CompositionLocalProvider(LocalBrand provides tokens) {
        MaterialTheme(
            colorScheme = colors,
            typography = remember(oneUi) { typography(oneUi) },
            shapes = remember(oneUi) { shapes(oneUi) },
            content = content,
        )
    }
}
