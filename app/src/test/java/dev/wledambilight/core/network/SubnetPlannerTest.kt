package dev.wledambilight.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class SubnetPlannerTest {

    private fun address(ip: String): Inet4Address = InetAddress.getByName(ip) as Inet4Address

    @Test
    fun `scan covers the local 24 without network, broadcast or self`() {
        val hosts = SubnetPlanner.hostsAround(address("192.168.1.42"))
        assertEquals(253, hosts.size)
        assertTrue(hosts.any { it.hostAddress == "192.168.1.1" })
        assertTrue(hosts.any { it.hostAddress == "192.168.1.254" })
        assertFalse(hosts.any { it.hostAddress == "192.168.1.42" })
        assertFalse(hosts.any { it.hostAddress == "192.168.1.0" })
        assertFalse(hosts.any { it.hostAddress == "192.168.1.255" })
    }

    @Test
    fun `subnet label is derived from the address`() {
        assertEquals("10.0.5.0/24", SubnetPlanner.subnetLabel(address("10.0.5.200")))
    }

    @Test
    fun `private ranges are recognised`() {
        assertTrue(NetworkUtils.isPrivateIpv4(address("10.1.2.3")))
        assertTrue(NetworkUtils.isPrivateIpv4(address("172.16.0.1")))
        assertTrue(NetworkUtils.isPrivateIpv4(address("172.31.255.254")))
        assertTrue(NetworkUtils.isPrivateIpv4(address("192.168.0.1")))
        assertTrue(NetworkUtils.isPrivateIpv4(address("169.254.1.1")))
        assertFalse(NetworkUtils.isPrivateIpv4(address("172.32.0.1")))
        assertFalse(NetworkUtils.isPrivateIpv4(address("8.8.8.8")))
    }
}
