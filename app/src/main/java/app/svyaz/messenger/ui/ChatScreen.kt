package app.svyaz.messenger.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.svyaz.messenger.core.ChatEntry
import app.svyaz.messenger.core.MAX_TEXT_BYTES
import app.svyaz.messenger.core.MessagePage
import app.svyaz.messenger.core.NodeRepository
import app.svyaz.messenger.core.canSend
import app.svyaz.messenger.core.deliveryLabel
import app.svyaz.messenger.core.displayName
import app.svyaz.messenger.core.formatTime
import app.svyaz.messenger.core.messageStatusLabel
import app.svyaz.messenger.core.utf8Size
import app.svyaz.messenger.ui.theme.LocalBrand
import kotlinx.coroutines.launch

private const val PAGE = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(repository: NodeRepository, peer: String, onBack: () -> Unit, onContact: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by repository.status.collectAsStateWithLifecycle()
    val revision by repository.revision.collectAsStateWithLifecycle()
    val settings by repository.settings.data.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    var page by remember { mutableStateOf(MessagePage()) }
    var limit by remember { mutableIntStateOf(PAGE) }
    var draft by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    DisposableEffect(peer) {
        repository.openChat = peer
        onDispose { repository.openChat = null }
    }

    LaunchedEffect(peer, revision, limit) {
        repository.loadMessages(peer, limit)?.let { loaded ->
            val grew = loaded.messages.lastOrNull()?.index != page.messages.lastOrNull()?.index
            page = loaded
            if (grew) listState.scrollToItem(0)
            loaded.messages.lastOrNull()?.let { repository.markRead(peer, it.index) }
        }
    }

    Scaffold(
        topBar = {
            val name = displayName(peer, settings?.names.orEmpty())
            val delivery = status?.peers?.get(peer)
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.combinedClickableCompat(onContact),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PeerAvatar(name, connected = delivery?.status == "connected", size = 38.dp, ring = brand.chatBar)
                        Column {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                deliveryLabel(delivery, System.currentTimeMillis()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = brand.chatBar, scrolledContainerColor = brand.chatBar),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            val newestFirst = page.messages.asReversed()
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(newestFirst, key = { _, entry -> entry.index }) { position, entry ->
                    fun sameSide(other: ChatEntry?) = other != null && (other.message.body.from != peer) == (entry.message.body.from != peer)
                    MessageBubble(
                        entry = entry,
                        outgoing = entry.message.body.from != peer,
                        joinedAbove = sameSide(newestFirst.getOrNull(position + 1)),
                        joinedBelow = sameSide(newestFirst.getOrNull(position - 1)),
                    )
                }
                if (page.hasMore) {
                    item(key = "more") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { limit += PAGE }) { Text("Показать раньше") }
                        }
                    }
                }
            }
            Composer(
                draft = draft,
                onDraft = { draft = it },
                onSend = {
                    val text = draft
                    scope.launch {
                        repository.send(peer, text)
                            .onSuccess { draft = "" }
                            .onFailure { snackbar.showSnackbar(it.message ?: "Не удалось поставить в очередь") }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val brand = LocalBrand.current
    val colors = MaterialTheme.colorScheme
    val bytes = utf8Size(draft)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (bytes > MAX_TEXT_BYTES - 1000) {
            Text(
                "$bytes / $MAX_TEXT_BYTES байт",
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (bytes > MAX_TEXT_BYTES) colors.error else colors.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(brand.field)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text("Сообщение", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                        }
                        field()
                    }
                },
            )
            FilledIconButton(
                onClick = onSend,
                enabled = canSend(draft),
                shape = brand.sendShape,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Отправить")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(entry: ChatEntry, outgoing: Boolean, joinedAbove: Boolean, joinedBelow: Boolean) {
    val context = LocalContext.current
    val brand = LocalBrand.current
    val body = entry.message.body
    val textColor = if (outgoing) brand.onBubbleOut else brand.onBubbleIn
    Box(
        Modifier.fillMaxWidth().padding(top = if (joinedAbove) 3.dp else 12.dp),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(brand.bubbleShape(outgoing, joinedAbove, joinedBelow))
                .background(if (outgoing) brand.bubbleOut else brand.bubbleIn)
                .combinedClickable(onClick = {}, onLongClick = { context.copyToClipboard(body.text) })
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(body.text, style = MaterialTheme.typography.bodyMedium, color = textColor)
            Text(
                if (outgoing) "${formatTime(body.time)} · ${messageStatusLabel(entry.status)}" else formatTime(body.time),
                modifier = Modifier.align(Alignment.End).alpha(0.72f),
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
            )
            if (entry.error.isNotEmpty()) {
                Text(
                    "Не доставлено. Сообщение сохранено, повторим автоматически.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
