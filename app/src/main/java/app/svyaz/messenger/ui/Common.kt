package app.svyaz.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.svyaz.messenger.core.avatarInitial
import app.svyaz.messenger.ui.theme.GroupShape
import app.svyaz.messenger.ui.theme.LocalBrand
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Room left under the last list row so the Material You floating button does not cover it. */
private val FAB_CLEARANCE = 88.dp

class BrandAction(val label: String, val icon: Painter, val onClick: () -> Unit)

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Назад")
    }
}

/**
 * Screen frame shared by both styles: a large header that collapses on scroll, and the screen's
 * actions, which One UI keeps in a bottom dock and Material You splits between the header and a
 * floating button. The padding passed to [content] already clears all of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    primaryAction: BrandAction? = null,
    secondaryActions: List<BrandAction> = emptyList(),
    content: @Composable (PaddingValues) -> Unit,
) {
    val brand = LocalBrand.current
    val colors = MaterialTheme.colorScheme
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val direction = LocalLayoutDirection.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { if (onBack != null) BackButton(onBack) },
                actions = {
                    if (!brand.oneUi) {
                        secondaryActions.forEach { action ->
                            IconButton(onClick = action.onClick) { Icon(action.icon, contentDescription = action.label) }
                        }
                    }
                },
                expandedHeight = brand.headerExpanded,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = brand.headerScrolled,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (brand.oneUi && primaryAction != null) BrandDock(listOf(primaryAction) + secondaryActions)
        },
        floatingActionButton = {
            if (!brand.oneUi && primaryAction != null) {
                ExtendedFloatingActionButton(
                    onClick = primaryAction.onClick,
                    icon = { Icon(primaryAction.icon, contentDescription = null) },
                    text = { Text(primaryAction.label) },
                    shape = RoundedCornerShape(18.dp),
                )
            }
        },
    ) { inner ->
        val clearance = if (!brand.oneUi && primaryAction != null) FAB_CLEARANCE else 0.dp
        content(
            PaddingValues(
                start = inner.calculateStartPadding(direction),
                top = inner.calculateTopPadding(),
                end = inner.calculateEndPadding(direction),
                bottom = inner.calculateBottomPadding() + clearance,
            ),
        )
    }
}

@Composable
private fun BrandDock(actions: List<BrandAction>) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        color = LocalBrand.current.groupColor,
        shadowElevation = 6.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            actions.forEachIndexed { index, action ->
                val tint = if (index == 0) colors.primary else colors.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = action.onClick),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(action.icon, contentDescription = null, tint = tint)
                    Text(action.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/** A titled block of rows: a rounded card in One UI, plain rows on the background in Material You. */
@Composable
fun BrandGroup(label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val brand = LocalBrand.current
    Column(Modifier.fillMaxWidth()) {
        if (label != null) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = if (brand.oneUi) 28.dp else 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                color = brand.groupLabel,
            )
        }
        val body = if (brand.oneUi) {
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(GroupShape).background(brand.groupColor)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(body, content = content)
    }
}

/** Free-form content inside a [BrandGroup], such as text fields, inset like a row. */
@Composable
fun GroupFields(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun GroupDivider(start: Dp = 18.dp) {
    if (LocalBrand.current.oneUi) {
        HorizontalDivider(
            modifier = Modifier.padding(start = start, end = 18.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
fun PeerAvatar(name: String, connected: Boolean, size: Dp = 46.dp, ring: Color = LocalBrand.current.rowColor) {
    val brand = LocalBrand.current
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(brand.avatarShape).background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(avatarInitial(name), style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
        }
        if (connected) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .background(ring, CircleShape)
                    .padding(2.5.dp)
                    .background(brand.online, CircleShape),
            )
        }
    }
}

/** Display-only switch: the row around it handles the toggle and its accessibility. */
@Composable
fun BrandSwitch(checked: Boolean, enabled: Boolean = true) {
    if (!LocalBrand.current.oneUi) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        return
    }
    val colors = MaterialTheme.colorScheme
    val thumb by animateDpAsState(if (checked) 23.dp else 3.dp, label = "switch")
    Box(
        Modifier
            .size(46.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .clip(CircleShape)
            .background(if (checked) colors.primary else colors.outline),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumb)
                .size(20.dp)
                .background(if (checked) colors.onPrimary else Color.White, CircleShape),
        )
    }
}

fun Context.copyToClipboard(text: String, label: String = "Скопировано") {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
    Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
}

fun Context.readClipboardText(): String =
    getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, "Поделиться"))
}

private fun qrBitmap(content: String): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0)
    val bitmap = Bitmap.createBitmap(matrix.width + 8, matrix.height + 8, Bitmap.Config.RGB_565)
    bitmap.eraseColor(android.graphics.Color.WHITE)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        if (matrix.get(x, y)) bitmap.setPixel(x + 4, y + 4, android.graphics.Color.BLACK)
    }
    return bitmap
}

@Composable
fun QrCode(content: String, size: Dp = 280.dp) {
    val image = remember(content) { qrBitmap(content).asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = "QR-код Peer ID",
        modifier = Modifier.size(size).clip(GroupShape),
        filterQuality = FilterQuality.None,
    )
}
