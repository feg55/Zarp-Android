package io.github.feg55.zarp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StrategyTest {
    @Before
    fun setUp() = TestLang.english()

    private fun byId(id: String) = StrategyCatalog.builtIn.single { it.id == id }

    @Test
    fun `catalog keeps Zarp ids and order`() {
        val zarpOrder = listOf(
            "warp-q-google6", "warp-q-google3", "warp-q-vk6", "warp-q-google-vk", "warp-q-google10",
            "warp-q-google-ttl", "warp-q-vk-ttl", "warp-q-google-bad",
            "warp-wg-google6", "warp-wg-stun", "warp-wg-vk10", "warp-wg-google-ttl",
            "warp-t-google-md5", "warp-t-seqovl", "warp-t-vk-seq", "warp-t-hostfake",
        )
        val ids = StrategyCatalog.builtIn.map { it.id }
        assertEquals(zarpOrder, ids.filter { it in zarpOrder })
        assertTrue(StrategyCatalog.DIRECT_ID in ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `quic fake strategies translate to fake steps`() {
        assertEquals(listOf(FakeStep(Blob.QuicGoogle, 6)), byId("warp-q-google6").plan.fakes)
        assertEquals(listOf(FakeStep(Blob.QuicGoogle, 3)), byId("warp-q-google3").plan.fakes)
        assertEquals(listOf(FakeStep(Blob.QuicVk, 6)), byId("warp-q-vk6").plan.fakes)
        assertEquals(listOf(FakeStep(Blob.QuicGoogle, 10)), byId("warp-q-google10").plan.fakes)
        assertEquals(
            listOf(FakeStep(Blob.QuicGoogle, 3), FakeStep(Blob.QuicVk, 3)),
            byId("warp-q-google-vk").plan.fakes,
        )
        assertEquals(listOf(FakeStep(Blob.QuicGoogle, 6, ipTtl = 4, ip6Ttl = 4)), byId("warp-q-google-ttl").plan.fakes)
        assertEquals(listOf(FakeStep(Blob.QuicVk, 6, ipTtl = 4, ip6Ttl = 4)), byId("warp-q-vk-ttl").plan.fakes)
    }

    @Test
    fun `strategies needing raw sockets are unsupported, not replaced`() {
        val badsum = byId("warp-q-google-bad")
        assertTrue(badsum.plan.unsupportedReason!!.contains("badsum"))
        assertTrue(badsum.plan.fakes.isEmpty())
        for (id in listOf("warp-t-google-md5", "warp-t-seqovl", "warp-t-vk-seq", "warp-t-hostfake")) {
            assertNotNull(id, byId(id).plan.unsupportedReason)
        }
        StrategyCatalog.builtIn.filter { it.transport == Transport.WireGuard }.forEach {
            assertNotNull(it.plan.unsupportedReason)
        }
    }

    @Test
    fun `http2 split and disorder map to core desync`() {
        assertEquals("split:1,midsld", byId("warp-t-split").plan.tcpDesync)
        assertEquals("disorder:1,midsld", byId("warp-t-disorder").plan.tcpDesync)
        assertNull(byId("warp-t-split").plan.unsupportedReason)
    }

    @Test
    fun `direct has an empty supported plan`() {
        val d = byId(StrategyCatalog.DIRECT_ID)
        assertTrue(d.supported)
        assertTrue(d.plan.fakes.isEmpty())
        assertNull(d.plan.tcpDesync)
    }

    @Test
    fun `parser rejects nonsense`() {
        assertNotNull(ZapretArgs.parse(Transport.MasqueH3, "--payload=tls_client_hello --lua-desync=fake:blob=quic_google").unsupportedReason)
        assertNotNull(ZapretArgs.parse(Transport.MasqueH3, "--lua-desync=fake:blob=nope").unsupportedReason)
        assertNotNull(ZapretArgs.parse(Transport.MasqueH3, "--lua-desync=fake:blob=quic_google:repeats=0").unsupportedReason)
        assertNotNull(ZapretArgs.parse(Transport.MasqueH3, "--wf-l3=ipv4").unsupportedReason)
        assertNotNull(ZapretArgs.parse(Transport.MasqueH2, "--lua-desync=multisplit:pos=1,middle").unsupportedReason)
        assertEquals(
            listOf(FakeStep(Blob.Zero64, 12)),
            ZapretArgs.parse(Transport.MasqueH3, "--lua-desync=fake:blob=zero64:repeats=12").fakes,
        )
    }

    @Test
    fun `custom strategies use the strategies_txt format`() {
        val skipped = mutableListOf<String>()
        val list = StrategyCatalog.parseCustom(
            """
            # comment
            My QUIC | h3 | --payload=quic_initial --lua-desync=fake:blob=quic_google:repeats=8
            broken line
            Split | h2 | --payload=tls_client_hello --lua-desync=multisplit:pos=2
            """.trimIndent(),
        ) { skipped += it }
        assertEquals(listOf("custom-my-quic", "custom-split"), list.map { it.id })
        assertEquals("★ My QUIC", list[0].name)
        assertEquals(listOf(FakeStep(Blob.QuicGoogle, 8)), list[0].plan.fakes)
        assertEquals("split:2", list[1].plan.tcpDesync)
        assertEquals(listOf("broken line"), skipped)
        assertTrue(StrategyCatalog.parseCustom(StrategyCatalog.CUSTOM_TEMPLATE).isEmpty())
    }

    @Test
    fun `transport aliases match Zarp`() {
        assertEquals(Transport.MasqueH3, Transport.parse("masque"))
        assertEquals(Transport.MasqueH2, Transport.parse(" H2 "))
        assertEquals(Transport.WireGuard, Transport.parse("wireguard"))
        assertNull(Transport.parse("tcp"))
    }
}
