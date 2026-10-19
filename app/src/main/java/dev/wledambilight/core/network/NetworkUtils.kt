package dev.wledambilight.core.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

object NetworkUtils {

    /** First site-local IPv4 address of an active non-loopback interface, or null. */
    fun localIpv4(): Inet4Address? = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    } catch (e: Exception) {
        null
    }

    fun isPrivateIpv4(address: Inet4Address): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    private fun <T> java.util.Enumeration<T>.toList(): List<T> {
        val result = ArrayList<T>()
        while (hasMoreElements()) result += nextElement()
        return result
    }
}

/**
 * Restricts discovery scans to the local /24. The user must trigger a scan explicitly; the app never
 * sweeps arbitrary ranges in the background.
 */
object SubnetPlanner {

    fun hostsAround(address: Inet4Address): List<Inet4Address> {
        val bytes = address.address
        if (bytes.size != 4) return emptyList()
        val self = bytes[3].toInt() and 0xFF
        return (1..254)
            .filter { it != self }
            .mapNotNull { last ->
                runCatching {
                    InetAddress.getByAddress(byteArrayOf(bytes[0], bytes[1], bytes[2], last.toByte())) as Inet4Address
                }.getOrNull()
            }
    }

    fun subnetLabel(address: Inet4Address): String {
        val bytes = address.address
        return "${bytes[0].toInt() and 0xFF}.${bytes[1].toInt() and 0xFF}.${bytes[2].toInt() and 0xFF}.0/24"
    }
}
