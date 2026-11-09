package app.svyaz.messenger.core

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import app.svyaz.messenger.service.NodeService
import gocore.Gocore
import gocore.Listener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

/** Owns the Go node's lifecycle and exposes its state to the service and the UI. */
class NodeRepository(private val context: Context, private val notifier: Notifier) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycle = Mutex()

    val settings = Settings(context, scope)

    /** noBackupFilesDir keeps identity.key out of every Android backup channel. */
    val dataDir: File = File(context.noBackupFilesDir, "profile")

    private val _status = MutableStateFlow<NodeStatus?>(null)
    val status: StateFlow<NodeStatus?> = _status.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** Incremented whenever history may have changed; chat screens reload on it. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val _startError = MutableStateFlow<String?>(null)
    val startError: StateFlow<String?> = _startError.asStateFlow()

    @Volatile var uiVisible = false
        private set

    /** The chat on screen; incoming messages for it raise no notification. */
    @Volatile var openChat: String? = null

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val listener = object : Listener {
        override fun onMessage(peer: String, id: String, text: String, time: Long) {
            if (uiVisible && openChat == peer) return
            scope.launch { notifier.showMessage(peer, text, settings.current().names) }
        }

        override fun onChanged() {
            refreshes.tryEmit(Unit)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = Gocore.networkChanged()
        override fun onLost(network: Network) = Gocore.networkChanged()
    }
    private var networkRegistered = false

    init {
        scope.launch { refreshes.collect { reload() } }
        // The retry countdown and connection list only matter while someone is looking.
        scope.launch {
            while (true) {
                delay(1000)
                if (uiVisible) readStatus()
            }
        }
    }

    private fun readStatus() {
        if (!Gocore.isRunning()) {
            _status.value = null
            return
        }
        runCatching { _status.value = CoreJson.decodeFromString<NodeStatus>(Gocore.status()) }
    }

    private suspend fun reload() = withContext(Dispatchers.IO) {
        readStatus()
        if (!Gocore.isRunning()) return@withContext
        if (uiVisible) {
            val marks = CoreJson.encodeToString(settings.current().readMarks)
            runCatching { _conversations.value = CoreJson.decodeFromString(Gocore.conversations(marks)) }
            _revision.value++
        }
    }

    fun requestRefresh() {
        refreshes.tryEmit(Unit)
    }

    private fun startLocked(config: AppSettings) {
        try {
            dataDir.mkdirs()
            Gocore.setAndroidVersion(Build.VERSION.RELEASE.substringBefore('.').toLongOrNull() ?: 11L)
            Gocore.start(dataDir.path, coreConfigJson(config.bootstrap, config.peerList), listener)
            _startError.value = null
            if (!networkRegistered) {
                context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
                networkRegistered = true
            }
        } catch (e: Exception) {
            _startError.value = e.message ?: e.toString()
        }
    }

    private fun stopLocked() {
        if (networkRegistered) {
            context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
            networkRegistered = false
        }
        Gocore.stop()
    }

    suspend fun start() {
        val config = settings.current()
        lifecycle.withLock { withContext(Dispatchers.IO) { startLocked(config) } }
        requestRefresh()
    }

    suspend fun stop() {
        lifecycle.withLock { withContext(Dispatchers.IO) { stopLocked() } }
        requestRefresh()
    }

    /** Applies changed network settings. */
    suspend fun restartIfRunning() {
        val config = settings.current()
        lifecycle.withLock {
            withContext(Dispatchers.IO) {
                if (Gocore.isRunning()) {
                    stopLocked()
                    startLocked(config)
                }
            }
        }
        requestRefresh()
    }

    /** Brings the node in line with the settings and whether the UI is on screen. */
    suspend fun syncRunState(visible: Boolean) {
        uiVisible = visible
        val config = settings.current()
        if (!config.onboarded) return
        val service = Intent(context, NodeService::class.java)
        when {
            // Android forbids starting a foreground service once the app is in the background.
            config.background -> if (visible) ContextCompat.startForegroundService(context, service)
            visible -> {
                context.stopService(service)
                start()
            }
            else -> {
                context.stopService(service)
                stop()
            }
        }
        requestRefresh()
    }

    fun localPeerId(): Result<String> = runCatching {
        dataDir.mkdirs()
        Gocore.localPeerID(dataDir.path)
    }

    suspend fun send(to: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching { Gocore.send(to, text) }.also { requestRefresh() }
    }

    suspend fun loadMessages(peer: String, limit: Int): MessagePage? = withContext(Dispatchers.IO) {
        runCatching { CoreJson.decodeFromString<MessagePage>(Gocore.messages(peer, limit.toLong(), -1)) }.getOrNull()
    }

    suspend fun markRead(peer: String, index: Int) {
        // Refreshing only on change: the chat screen calls this after every reload.
        if (settings.markRead(peer, index)) {
            notifier.clear(peer)
            requestRefresh()
        }
    }

    private suspend fun <T> withNodeStopped(block: () -> T): Result<T> {
        val config = settings.current()
        val result = lifecycle.withLock {
            withContext(Dispatchers.IO) {
                val wasRunning = Gocore.isRunning()
                if (wasRunning) stopLocked()
                val outcome = runCatching(block)
                if (wasRunning) startLocked(config)
                outcome
            }
        }
        requestRefresh()
        return result
    }

    suspend fun exportProfile(target: Uri): Result<Unit> = withNodeStopped {
        val temp = File(context.cacheDir, "profile-export.zip")
        try {
            Gocore.exportProfile(dataDir.path, temp.path)
            val output = context.contentResolver.openOutputStream(target, "wt")
                ?: error("Не удалось открыть файл для записи")
            output.use { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }

    suspend fun importProfile(source: Uri): Result<Unit> {
        val result = withNodeStopped {
            val temp = File(context.cacheDir, "profile-import.zip")
            try {
                val input = context.contentResolver.openInputStream(source)
                    ?: error("Не удалось открыть файл")
                input.use { from -> temp.outputStream().use { from.copyTo(it) } }
                dataDir.mkdirs()
                Gocore.importProfile(dataDir.path, temp.path)
            } finally {
                temp.delete()
            }
        }
        if (result.isSuccess) settings.clearReadMarks()
        requestRefresh()
        return result
    }
}
