package app.svyaz.messenger.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Go marshals nil slices and maps as null; coercion maps them to the defaults below. */
val CoreJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class PeerDelivery(
    val status: String = "",
    val detail: String = "",
    val failures: Int = 0,
    @SerialName("next_attempt") val nextAttempt: Long = 0,
)

@Serializable
data class Connection(
    val peer: String = "",
    val address: String = "",
    val relay: Boolean = false,
)

@Serializable
data class DiscoveryPeer(
    val peer: String = "",
    @SerialName("rtt_ms") val rttMillis: Double = 0.0,
)

@Serializable
data class DiscoveryStatus(
    val source: String = "",
    val candidates: Int = 0,
    val selected: List<DiscoveryPeer> = emptyList(),
    val updated: Long = 0,
    val cached: Boolean = false,
    val error: String = "",
)

@Serializable
data class NodeStatus(
    val peer: String = "",
    val reachability: String = "Unknown",
    val addresses: List<String> = emptyList(),
    val connections: List<Connection> = emptyList(),
    val queued: Int = 0,
    val peers: Map<String, PeerDelivery> = emptyMap(),
    val discovery: DiscoveryStatus = DiscoveryStatus(),
)

@Serializable
data class Conversation(
    val peer: String = "",
    @SerialName("last_text") val lastText: String = "",
    @SerialName("last_time") val lastTime: Long = 0,
    @SerialName("last_index") val lastIndex: Int = 0,
    val outgoing: Boolean = false,
    val unread: Int = 0,
    val queued: Int = 0,
)

@Serializable
data class MessageBody(
    val id: String = "",
    val from: String = "",
    val to: String = "",
    val text: String = "",
    val time: Long = 0,
)

@Serializable
data class SignedMessage(val body: MessageBody = MessageBody())

@Serializable
data class ChatEntry(
    val index: Int = 0,
    val message: SignedMessage = SignedMessage(),
    val status: String = "",
    val error: String = "",
)

@Serializable
data class MessagePage(
    val messages: List<ChatEntry> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)
