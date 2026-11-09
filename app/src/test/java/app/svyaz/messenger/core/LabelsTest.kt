package app.svyaz.messenger.core

import app.svyaz.messenger.ui.theme.BrandStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    @Test
    fun networkSummaryClaimsDirectOnlyWithoutRelays() {
        assertEquals("Нет соединений", networkSummary(0, 0, 0))
        assertEquals("Напрямую · 1 соединение", networkSummary(1, 1, 0))
        assertEquals("Напрямую · 4 соединения", networkSummary(4, 4, 0))
        assertEquals("12 соединений", networkSummary(12, 11, 0))
        assertEquals("Напрямую · 21 соединение · в очереди 3", networkSummary(21, 21, 3))
    }

    @Test
    fun avatarInitialSkipsDecoration() {
        assertEquals("А", avatarInitial("аня"))
        assertEquals("X", avatarInitial("…x7Qa"))
        assertEquals("?", avatarInitial("…"))
    }

    @Test
    fun autoStyleFollowsManufacturer() {
        assertEquals(BrandStyle.ONE_UI, BrandStyle.AUTO.resolve("samsung"))
        assertEquals(BrandStyle.MATERIAL_YOU, BrandStyle.AUTO.resolve("Google"))
        assertEquals(BrandStyle.MATERIAL_YOU, BrandStyle.MATERIAL_YOU.resolve("samsung"))
        assertEquals(BrandStyle.AUTO, BrandStyle.fromKey("unknown"))
    }

    @Test
    fun deliveryLabelMatchesDesktopWording() {
        assertEquals("нет активного соединения", deliveryLabel(null, 0))
        assertEquals("на связи", deliveryLabel(PeerDelivery(status = "connected"), 0))
        assertEquals(
            "не в сети / недоступен · повтор через 5 с",
            deliveryLabel(PeerDelivery(status = "unavailable", nextAttempt = 5000), 1),
        )
        assertEquals(
            "сбой доставки · повтор через 0 с",
            deliveryLabel(PeerDelivery(status = "delivery_failed", nextAttempt = 10), 5000),
        )
    }

    @Test
    fun sendLimitCountsUtf8Bytes() {
        assertFalse(canSend("   \n"))
        assertTrue(canSend("а".repeat(8000)))
        assertFalse(canSend("а".repeat(8001)))
    }

    @Test
    fun namesFallBackToShortPeerId() {
        val peer = "12D3KooWAbcdefghijklmnopqrstuvwxyz"
        assertEquals("…qrstuvwxyz".length + 2, shortPeer(peer).length)
        assertEquals(shortPeer(peer), displayName(peer, mapOf(peer to "  ")))
        assertEquals("Аня", displayName(peer, mapOf(peer to "Аня")))
    }

    @Test
    fun configUsesGoFieldNames() {
        assertEquals(
            """{"bootstrap":"/a,/b","peer_list":"off"}""",
            coreConfigJson(listOf("/a", "/b"), "off"),
        )
    }

    @Test
    fun nullCollectionsFromGoDecodeToEmpty() {
        val status = CoreJson.decodeFromString<NodeStatus>(
            """{"peer":"p","reachability":"Private","addresses":null,"connections":null,"queued":2,"peers":{"x":{"status":"queued"}},"discovery":{"candidates":0,"selected":null,"cached":false}}""",
        )
        assertEquals(emptyList<String>(), status.addresses)
        assertEquals(emptyList<DiscoveryPeer>(), status.discovery.selected)
        assertEquals("queued", status.peers["x"]?.status)
    }

    @Test
    fun embeddedGoEntryDecodes() {
        val page = CoreJson.decodeFromString<MessagePage>(
            """{"messages":[{"index":3,"message":{"body":{"domain":"d","id":"i","from":"f","to":"t","text":"привет","time":7},"signature":"AQ=="},"status":"received"}],"has_more":true}""",
        )
        assertEquals(3, page.messages[0].index)
        assertEquals("привет", page.messages[0].message.body.text)
        assertTrue(page.hasMore)
    }
}
