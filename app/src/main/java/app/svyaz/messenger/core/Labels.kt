package app.svyaz.messenger.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val MAX_TEXT_BYTES = 16000
const val MAX_BOOTSTRAP = 16

/** Port of peerDeliveryLabel from the desktop main.go. */
fun deliveryLabel(delivery: PeerDelivery?, nowMillis: Long): String {
    val base = when (delivery?.status) {
        "looking_up" -> "ищем собеседника"
        "connecting" -> "подключаемся"
        "sending" -> "отправляем"
        "connected" -> "на связи"
        "unavailable" -> "не в сети / недоступен"
        "connect_failed" -> "не удалось подключиться"
        "delivery_failed" -> "сбой доставки"
        "rejected" -> "получатель отклонил сообщение"
        "queued" -> "ожидает отправки"
        else -> "нет активного соединения"
    }
    val next = delivery?.nextAttempt ?: 0
    if (next <= 0) return base
    val seconds = maxOf(0L, (next - nowMillis + 999) / 1000)
    return "$base · повтор через $seconds с"
}

fun messageStatusLabel(status: String): String = when (status) {
    "queued" -> "в очереди"
    "delivered" -> "доставлено"
    "received" -> "получено"
    else -> status
}

fun reachabilityLabel(reachability: String): String = when (reachability) {
    "Public" -> "публичный"
    "Private" -> "за NAT"
    else -> "определяем доступность"
}

private fun connectionsWord(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    return when {
        lastTwo in 11..14 -> "соединений"
        last == 1 -> "соединение"
        last in 2..4 -> "соединения"
        else -> "соединений"
    }
}

/** Second line of the chats header. "Напрямую" is claimed only when no connection goes through a relay. */
fun networkSummary(total: Int, direct: Int, queued: Int): String = buildString {
    when {
        total == 0 -> append("Нет соединений")
        direct == total -> append("Напрямую · $total ${connectionsWord(total)}")
        else -> append("$total ${connectionsWord(total)}")
    }
    if (queued > 0) append(" · в очереди $queued")
}

fun avatarInitial(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

fun shortPeer(peer: String): String = if (peer.length > 12) "…" + peer.takeLast(12) else peer

fun displayName(peer: String, names: Map<String, String>): String =
    names[peer]?.takeIf { it.isNotBlank() } ?: shortPeer(peer)

fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/** Mirrors the checks of the node's queue(), so the send button matches what Go accepts. */
fun canSend(text: String): Boolean = text.isNotBlank() && utf8Size(text) <= MAX_TEXT_BYTES

fun formatTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val a = Calendar.getInstance().apply { timeInMillis = millis }
    val b = Calendar.getInstance().apply { timeInMillis = now }
    val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd.MM HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

@Serializable
private data class CoreConfig(
    val bootstrap: String,
    @kotlinx.serialization.SerialName("peer_list") val peerList: String,
)

fun coreConfigJson(bootstrap: List<String>, peerList: String): String =
    CoreJson.encodeToString(CoreConfig(bootstrap.joinToString(","), peerList))
