package app.svyaz.messenger.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.svyaz.messenger.ui.theme.BrandStyle
import kotlinx.serialization.encodeToString

data class AppSettings(
    val onboarded: Boolean = false,
    val bootstrap: List<String> = emptyList(),
    val peerList: String = "auto",
    val background: Boolean = true,
    val autostart: Boolean = false,
    val names: Map<String, String> = emptyMap(),
    val readMarks: Map<String, Int> = emptyMap(),
    val style: BrandStyle = BrandStyle.AUTO,
)

private val Context.dataStore by preferencesDataStore("settings")

class Settings(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    val data: StateFlow<AppSettings?> = store.data
        .map<Preferences, AppSettings?> { parse(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(): AppSettings = data.filterNotNull().first()

    private fun parse(p: Preferences) = AppSettings(
        onboarded = p[ONBOARDED] ?: false,
        bootstrap = p[BOOTSTRAP]?.lines()?.filter { it.isNotBlank() } ?: emptyList(),
        peerList = p[PEER_LIST] ?: "auto",
        background = p[BACKGROUND] ?: true,
        autostart = p[AUTOSTART] ?: false,
        names = p[NAMES]?.let { CoreJson.decodeFromString<Map<String, String>>(it) } ?: emptyMap(),
        readMarks = p[READ_MARKS]?.let { CoreJson.decodeFromString<Map<String, Int>>(it) } ?: emptyMap(),
        style = BrandStyle.fromKey(p[STYLE]),
    )

    suspend fun setOnboarded() = store.edit { it[ONBOARDED] = true }.let { }
    suspend fun setBootstrap(list: List<String>) = store.edit { it[BOOTSTRAP] = list.joinToString("\n") }.let { }
    suspend fun setPeerList(value: String) = store.edit { it[PEER_LIST] = value }.let { }
    suspend fun setBackground(value: Boolean) = store.edit { it[BACKGROUND] = value }.let { }
    suspend fun setAutostart(value: Boolean) = store.edit { it[AUTOSTART] = value }.let { }
    suspend fun setStyle(value: BrandStyle) = store.edit { it[STYLE] = value.key }.let { }

    suspend fun setName(peer: String, name: String) {
        store.edit { p ->
            val names = p[NAMES]?.let { CoreJson.decodeFromString<Map<String, String>>(it) } ?: emptyMap()
            val updated = if (name.isBlank()) names - peer else names + (peer to name.trim())
            p[NAMES] = CoreJson.encodeToString(updated)
        }
    }

    /** Returns true if the mark moved forward. */
    suspend fun markRead(peer: String, index: Int): Boolean {
        var advanced = false
        store.edit { p ->
            val marks = p[READ_MARKS]?.let { CoreJson.decodeFromString<Map<String, Int>>(it) } ?: emptyMap()
            if ((marks[peer] ?: -1) < index) {
                p[READ_MARKS] = CoreJson.encodeToString(marks + (peer to index))
                advanced = true
            }
        }
        return advanced
    }

    /** History indexes change when another profile is imported. */
    suspend fun clearReadMarks() = store.edit { it.remove(READ_MARKS) }.let { }

    private companion object {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val BOOTSTRAP = stringPreferencesKey("bootstrap")
        val PEER_LIST = stringPreferencesKey("peer_list")
        val BACKGROUND = booleanPreferencesKey("background")
        val AUTOSTART = booleanPreferencesKey("autostart")
        val NAMES = stringPreferencesKey("names")
        val READ_MARKS = stringPreferencesKey("read_marks")
        val STYLE = stringPreferencesKey("style")
    }
}
