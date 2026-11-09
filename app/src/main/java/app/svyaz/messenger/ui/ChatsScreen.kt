package app.svyaz.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.svyaz.messenger.R
import app.svyaz.messenger.core.Conversation
import app.svyaz.messenger.core.NodeRepository
import app.svyaz.messenger.core.NodeStatus
import app.svyaz.messenger.core.displayName
import app.svyaz.messenger.core.formatTime
import app.svyaz.messenger.core.networkSummary
import app.svyaz.messenger.ui.theme.GroupShape
import app.svyaz.messenger.ui.theme.LocalBrand
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import gocore.Gocore

private fun needsBootstrap(status: NodeStatus?, bootstrapCount: Int): Boolean =
    status != null && bootstrapCount == 0 && status.discovery.candidates == 0

@Composable
fun ChatsScreen(
    repository: NodeRepository,
    onOpenChat: (String) -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
) {
    val status by repository.status.collectAsStateWithLifecycle()
    val conversations by repository.conversations.collectAsStateWithLifecycle()
    val settings by repository.settings.data.collectAsStateWithLifecycle()
    val startError by repository.startError.collectAsStateWithLifecycle()
    val brand = LocalBrand.current
    var showNew by remember { mutableStateOf(false) }

    BrandScaffold(
        title = "Беседы",
        subtitle = status?.let { s -> networkSummary(s.connections.size, s.connections.count { !it.relay }, s.queued) } ?: "Подключаемся…",
        primaryAction = BrandAction("Новая беседа", rememberVectorPainter(Icons.Rounded.Add)) { showNew = true },
        secondaryActions = listOf(
            BrandAction("Сеть", painterResource(R.drawable.ic_network), onNetwork),
            BrandAction("Настройки", painterResource(R.drawable.ic_tune), onSettings),
        ),
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            startError?.let { error -> item(key = "start-error") { Banner("Не удалось подключиться: $error") } }
            if (needsBootstrap(status, settings?.bootstrap?.size ?: 0)) {
                item(key = "bootstrap") {
                    Banner("Пока некому помочь найти собеседника. Добавьте адрес узла в настройках.", onSettings)
                }
            }
            if (conversations.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillParentMaxHeight(0.5f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Здесь появятся ваши разговоры. Добавьте собеседника по QR-коду или ID.",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(conversations, key = { _, c -> c.peer }) { index, c ->
                    Column(brand.listItem(index, conversations.size)) {
                        if (index > 0) GroupDivider(start = 74.dp)
                        ConversationRow(
                            conversation = c,
                            name = displayName(c.peer, settings?.names.orEmpty()),
                            connected = status?.peers?.get(c.peer)?.status == "connected",
                            onClick = { onOpenChat(c.peer) },
                        )
                    }
                }
            }
        }
    }

    if (showNew) {
        NewChatDialog(ownPeer = status?.peer, onDismiss = { showNew = false }) { peer ->
            showNew = false
            onOpenChat(peer)
        }
    }
}

@Composable
private fun Banner(text: String, onClick: (() -> Unit)? = null) {
    Card(
        shape = GroupShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, name: String, connected: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PeerAvatar(name, connected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (conversation.outgoing) "Вы: " else "") + conversation.lastText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(formatTime(conversation.lastTime), style = MaterialTheme.typography.bodySmall, color = muted)
            if (conversation.unread > 0) {
                Badge(containerColor = brand.unread, contentColor = brand.onUnread) { Text(conversation.unread.toString()) }
            }
        }
    }
}

@Composable
private fun NewChatDialog(ownPeer: String?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun tryOpen(raw: String) {
        val peer = raw.trim()
        val invalid = runCatching { Gocore.validatePeerID(peer) }.exceptionOrNull()
        when {
            invalid != null -> error = invalid.message
            peer == ownPeer -> error = "Выберите другого пира"
            else -> onOpen(peer)
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { value = it.trim(); tryOpen(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая беседа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text("Peer ID") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { value = context.readClipboardText().trim(); error = null }) { Text("Вставить") }
                    TextButton(onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Наведите камеру на QR-код Peer ID")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    }) { Text("Сканировать QR") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { tryOpen(value) }) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
