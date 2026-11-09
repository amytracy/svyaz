package app.svyaz.messenger.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.svyaz.messenger.App
import app.svyaz.messenger.ui.theme.AppTheme
import app.svyaz.messenger.ui.theme.BrandStyle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingPeer = mutableStateOf<String?>(null)
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingPeer.value = intent.getStringExtra(EXTRA_PEER)
        setContent {
            val settings by app.repository.settings.data.collectAsStateWithLifecycle()
            AppTheme(style = settings?.style ?: BrandStyle.AUTO) {
                AppRoot(
                    repository = app.repository,
                    pendingPeer = pendingPeer.value,
                    onPeerOpened = { pendingPeer.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingPeer.value = intent.getStringExtra(EXTRA_PEER)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { app.repository.syncRunState(visible = true) }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            // lifecycleScope may stop before this finishes, so the work is not tied to it.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                app.repository.syncRunState(visible = false)
            }
        }
    }

    companion object {
        const val EXTRA_PEER = "peer"
    }
}
