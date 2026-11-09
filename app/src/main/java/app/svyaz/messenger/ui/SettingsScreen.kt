package app.svyaz.messenger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.svyaz.messenger.core.MAX_BOOTSTRAP
import app.svyaz.messenger.core.NodeRepository
import app.svyaz.messenger.ui.theme.BrandStyle
import app.svyaz.messenger.ui.theme.LocalBrand
import gocore.Gocore
import kotlinx.coroutines.launch

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = if (LocalBrand.current.oneUi) 18.dp else 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun NavRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    SettingsRow(title, subtitle, Modifier.clickable(onClick = onClick)) {
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SettingsRow(
        title = title,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        enabled = enabled,
    ) { BrandSwitch(checked, enabled) }
}

@Composable
private fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit) {
    SettingsRow(title, modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)) {
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun StylePicker(selected: BrandStyle, onSelect: (BrandStyle) -> Unit) {
    val styles = BrandStyle.entries
    if (LocalBrand.current.oneUi) {
        Column(Modifier.selectableGroup()) {
            styles.forEachIndexed { index, style ->
                if (index > 0) GroupDivider()
                RadioRow(style.title, style == selected) { onSelect(style) }
            }
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        styles.forEachIndexed { index, style ->
            val isOn = style == selected
            val shape = when {
                isOn -> RoundedCornerShape(20.dp)
                index == 0 -> RoundedCornerShape(20.dp, 6.dp, 6.dp, 20.dp)
                index == styles.lastIndex -> RoundedCornerShape(6.dp, 20.dp, 20.dp, 6.dp)
                else -> RoundedCornerShape(6.dp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(if (isOn) colors.primary else colors.surfaceContainerHigh)
                    .selectable(selected = isOn, role = Role.RadioButton, onClick = { onSelect(style) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    style.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isOn) colors.onPrimary else colors.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@SuppressLint("BatteryLife") // A P2P node has no push channel, the exemption is the core use case.
@Composable
fun SettingsScreen(repository: NodeRepository, onBack: () -> Unit, onMyQr: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsState by repository.settings.data.collectAsStateWithLifecycle()
    val settings = settingsState ?: return
    var newAddress by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf<String?>(null) }
    var customList by remember(settings.peerList) {
        mutableStateOf(settings.peerList.takeIf { it != "auto" && it != "off" }.orEmpty())
    }
    var confirmExport by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    fun saveBootstrap(list: List<String>) = scope.launch {
        repository.settings.setBootstrap(list)
        repository.restartIfRunning()
    }

    fun savePeerList(value: String) = scope.launch {
        repository.settings.setPeerList(value)
        repository.restartIfRunning()
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                repository.exportProfile(uri)
                    .onSuccess { toast("Профиль сохранён") }
                    .onFailure { toast("Ошибка экспорта: ${it.message}") }
            }
        }
    }
    var localNetworkGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
    }
    val localNetworkRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        localNetworkGranted = granted
        if (granted) scope.launch { repository.restartIfRunning() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    BrandScaffold(title = "Настройки", onBack = onBack) { padding ->
        Column(
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrandGroup("Оформление") {
                StylePicker(settings.style) { style -> scope.launch { repository.settings.setStyle(style) } }
            }

            BrandGroup("Профиль") {
                NavRow("Мой QR-код и Peer ID", "Покажите их собеседнику", onMyQr)
                GroupDivider()
                NavRow("Экспортировать профиль", "Архив для настольной версии: распакуйте его в каталог --data") { confirmExport = true }
                GroupDivider()
                NavRow("Импортировать профиль") { importer.launch(arrayOf("application/zip", "application/octet-stream")) }
            }

            BrandGroup("Фоновая работа") {
                SwitchRow("Принимать сообщения при закрытом приложении", settings.background) { value ->
                    scope.launch {
                        repository.settings.setBackground(value)
                        repository.syncRunState(visible = true)
                    }
                }
                GroupDivider()
                SwitchRow("Запускать после перезагрузки", settings.autostart, enabled = settings.background) { value ->
                    scope.launch { repository.settings.setAutostart(value) }
                }
                val power = context.getSystemService(PowerManager::class.java)
                if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                    GroupDivider()
                    NavRow("Отключить оптимизацию батареи") {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
            }

            BrandGroup("Публичные узлы") {
                if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK && !localNetworkGranted) {
                    NavRow("Разрешить доступ к локальной сети") { localNetworkRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
                    GroupDivider()
                }
                GroupFields {
                    settings.bootstrap.forEach { address ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(address, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { saveBootstrap(settings.bootstrap - address) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить адрес")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = newAddress,
                        onValueChange = { newAddress = it; addressError = null },
                        label = { Text("/ip4/IP/tcp/80/ws/p2p/PEER_ID") },
                        isError = addressError != null,
                        supportingText = addressError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = newAddress.isNotBlank() && settings.bootstrap.size < MAX_BOOTSTRAP,
                        onClick = {
                            val value = newAddress.trim()
                            val invalid = runCatching { Gocore.validateBootstrap(value) }.exceptionOrNull()
                            if (invalid != null) {
                                addressError = invalid.message
                            } else {
                                if (value !in settings.bootstrap) saveBootstrap(settings.bootstrap + value)
                                newAddress = ""
                            }
                        },
                    ) { Text("Добавить адрес") }
                }
            }

            BrandGroup("Общий список узлов") {
                Column(Modifier.selectableGroup()) {
                    listOf("auto" to "Авто", "off" to "Выключен").forEachIndexed { index, (value, title) ->
                        if (index > 0) GroupDivider()
                        RadioRow(title, settings.peerList == value) { savePeerList(value) }
                    }
                }
                GroupDivider()
                GroupFields {
                    Text(
                        "В режиме «Авто» список не используется, если указан свой адрес узла: так ваша сеть остаётся отдельной.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = customList,
                        onValueChange = { customList = it },
                        label = { Text("Свой список (HTTPS URL)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = customList.startsWith("https://") && customList != settings.peerList,
                        onClick = { savePeerList(customList.trim()) },
                    ) { Text("Использовать этот список") }
                }
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Экспорт профиля") },
            text = { Text("Архив не зашифрован. Он содержит ваш приватный ключ и всю переписку: тот, кто получит файл, сможет выдавать себя за вас.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    exporter.launch("svyaz-profile.zip")
                }) { Text("Понятно, экспортировать") }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Отмена") } },
        )
    }

    importUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { importUri = null },
            title = { Text("Импорт профиля") },
            text = { Text("Текущий Peer ID и вся переписка на этом устройстве будут заменены содержимым архива. Отменить это нельзя.") },
            confirmButton = {
                TextButton(onClick = {
                    importUri = null
                    scope.launch {
                        repository.importProfile(uri)
                            .onSuccess { toast("Профиль импортирован") }
                            .onFailure { toast("Ошибка импорта: ${it.message}") }
                    }
                }) { Text("Заменить") }
            },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("Отмена") } },
        )
    }
}
