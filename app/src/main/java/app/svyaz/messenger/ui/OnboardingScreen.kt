package app.svyaz.messenger.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.svyaz.messenger.R
import app.svyaz.messenger.core.NodeRepository
import app.svyaz.messenger.ui.theme.GroupShape
import app.svyaz.messenger.ui.theme.LocalBrand
import gocore.Gocore
import kotlinx.coroutines.launch

fun runtimePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
}.toTypedArray()

const val LOCAL_NETWORK_SDK = 37

@Composable
private fun OnboardingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val brand = LocalBrand.current
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(if (brand.oneUi) GroupShape else MaterialTheme.shapes.large)
            .background(brand.field)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        content()
    }
}

@Composable
fun OnboardingScreen(repository: NodeRepository, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val peerId = remember { repository.localPeerId() }
    var bootstrap by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val finish = {
        scope.launch {
            val value = bootstrap.trim()
            if (value.isNotEmpty()) repository.settings.setBootstrap(listOf(value))
            repository.settings.setOnboarded()
            repository.syncRunState(visible = true)
            onDone()
        }
        Unit
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }

    Scaffold { padding ->
        BoxWithConstraints(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(104.dp), tint = colors.primary)
                    Icon(painterResource(R.drawable.ic_wordmark), contentDescription = "Связь", tint = colors.onBackground)
                    Text("Разговор напрямую, без серверов посередине", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Сообщение дойдёт, когда в сети и вы, и собеседник.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OnboardingCard("Ваш Peer ID") {
                        SelectionContainer {
                            Text(
                                peerId.getOrElse { "Не удалось создать профиль: ${it.message}" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                    OnboardingCard("Публичный узел, необязательно") {
                        Text(
                            "Общий список узлов пока пуст. Чтобы найти собеседника, укажите адрес запущенного узла. Его можно добавить и позже в настройках.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = bootstrap,
                            onValueChange = { bootstrap = it; error = null },
                            label = { Text("/ip4/IP/tcp/80/ws/p2p/PEER_ID") },
                            isError = error != null,
                            supportingText = error?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        enabled = peerId.isSuccess,
                        onClick = {
                            val value = bootstrap.trim()
                            val invalid = if (value.isEmpty()) null else runCatching { Gocore.validateBootstrap(value) }.exceptionOrNull()
                            if (invalid != null) {
                                error = invalid.message
                            } else {
                                val wanted = runtimePermissions()
                                if (wanted.isEmpty()) finish() else permissions.launch(wanted)
                            }
                        },
                    ) { Text("Начать") }
                }
            }
        }
    }
}
