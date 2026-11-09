package app.svyaz.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.svyaz.messenger.core.NodeRepository
import app.svyaz.messenger.core.deliveryLabel
import app.svyaz.messenger.core.reachabilityLabel
import kotlinx.coroutines.launch

@Composable
private fun PeerIdActions(peer: String) {
    val context = LocalContext.current
    SelectionContainer { Text(peer) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { context.copyToClipboard(peer) }) { Text("Копировать") }
        OutlinedButton(onClick = { context.shareText(peer) }) { Text("Поделиться") }
    }
}

@Composable
fun ContactScreen(repository: NodeRepository, peer: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by repository.settings.data.collectAsStateWithLifecycle()
    var name by remember(settings == null) { mutableStateOf(settings?.names?.get(peer).orEmpty()) }

    BrandScaffold(title = "Контакт", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя (видно только вам)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        repository.settings.setName(peer, name)
                        onBack()
                    }
                },
            ) { Text("Сохранить") }
            QrCode(peer)
            PeerIdActions(peer)
        }
    }
}

@Composable
fun MyQrScreen(repository: NodeRepository, onBack: () -> Unit) {
    val peerId = remember { repository.localPeerId() }
    BrandScaffold(title = "Мой Peer ID", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            peerId.onSuccess { id ->
                QrCode(id)
                PeerIdActions(id)
            }.onFailure { Text("Не удалось прочитать профиль: ${it.message}") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    BrandGroup(title) {
        GroupFields { content() }
    }
}

@Composable
fun NetworkScreen(repository: NodeRepository, onBack: () -> Unit) {
    val status by repository.status.collectAsStateWithLifecycle()
    val startError by repository.startError.collectAsStateWithLifecycle()

    BrandScaffold(title = "Сеть", onBack = onBack) { padding ->
        SelectionContainer {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                startError?.let {
                    Text("Не удалось подключиться: $it", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                }
                val s = status
                if (s == null) {
                    Text("Узел не запущен", Modifier.padding(horizontal = 16.dp))
                    return@Column
                }
                Section("Ваш Peer ID") { Text(s.peer) }
                Section("Доступность") { Text(reachabilityLabel(s.reachability)) }
                Section("Адреса") {
                    if (s.addresses.isEmpty()) Text("Адресов пока нет")
                    s.addresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Section("Соединения: ${s.connections.size}") {
                    if (s.connections.isEmpty()) Text("Соединений пока нет")
                    s.connections.forEach { c ->
                        Text(c.peer, style = MaterialTheme.typography.bodySmall)
                        Text(
                            (if (c.relay) "реле" else "прямое") + " · " + c.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val d = s.discovery
                if (d.source.isNotEmpty()) {
                    Section("Общий список узлов") {
                        Text(d.source, style = MaterialTheme.typography.bodySmall)
                        Text("Известно: ${d.candidates} · выбрано: ${d.selected.size}")
                        if (d.cached) Text("Используется сохранённая копия списка")
                        if (d.error.isNotEmpty()) Text(d.error, color = MaterialTheme.colorScheme.error)
                        d.selected.forEach {
                            Text("${it.peer} · RTT ${"%.1f".format(it.rttMillis)} мс", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Section("Доставка собеседникам") {
                    if (s.peers.isEmpty()) Text("Нет данных")
                    s.peers.toSortedMap().forEach { (id, delivery) ->
                        Text(id, style = MaterialTheme.typography.bodySmall)
                        Text(deliveryLabel(delivery, System.currentTimeMillis()))
                        if (delivery.detail.isNotEmpty()) {
                            Text(delivery.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
