package dev.wledambilight.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

data class ScanHit(
    val host: String,
    val name: String?,
    val version: String?,
    val latencyMs: Long,
)

/**
 * User-triggered, bounded probe of the local /24. Concurrency is capped so a weak Wi-Fi network and
 * the controller are not flooded; every request times out quickly and is cancelled when the caller
 * leaves the screen.
 */
class SubnetScanner(
    private val http: WledHttpClient = WledHttpClient(shortClient()),
    private val timeoutMillis: Long = 1500,
) {
    suspend fun scan(
        hosts: List<Inet4Address>,
        httpPort: Int = 80,
        concurrency: Int = 24,
    ): List<ScanHit> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 64))
        hosts.map { host ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val address = host.hostAddress ?: return@withPermit null
                    try {
                        val probe = withTimeout(timeoutMillis) {
                            http.probe(address, httpPort)
                        }
                        ScanHit(
                            host = address,
                            name = probe.info.name,
                            version = probe.info.ver,
                            latencyMs = probe.latencyMs,
                        )
                    } catch (timeout: TimeoutCancellationException) {
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    companion object {
        fun shortClient(): okhttp3.OkHttpClient = WledHttpClient.defaultClient().newBuilder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(900, TimeUnit.MILLISECONDS)
            .writeTimeout(900, TimeUnit.MILLISECONDS)
            .callTimeout(1200, TimeUnit.MILLISECONDS)
            .build()
    }
}
