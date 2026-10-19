package dev.wledambilight.core.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dev.wledambilight.core.data.DiagnosticsRepository
import dev.wledambilight.core.model.DiagnosticCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val source: DiscoverySource = DiscoverySource.MDNS,
)

enum class DiscoverySource {
    MDNS,
    SCAN,
}

/**
 * Android NSD/mDNS discovery for WLED's advertised `_wled._tcp` service.
 *
 * WLED only advertises mDNS when the corresponding option is enabled; many networks block multicast
 * entirely. Failures are reported but never thrown as a crash: the UI always offers manual setup and
 * an explicit /24 scan.
 */
class WledDiscovery(
    context: Context,
    private val diagnostics: DiagnosticsRepository,
) {
    private val appContext = context.applicationContext

    fun discover(timeoutMillis: Long = 12_000): Flow<DiscoveredDevice> = callbackFlow {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, "NsdManager unavailable")
            close()
            return@callbackFlow
        }

        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = try {
            wifi?.createMulticastLock("wled-ambilight-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            null
        }

        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val resolverJob = launch(Dispatchers.IO) {
            for (info in queue) {
                resolveOne(nsd, info).forEach { discovered ->
                    diagnostics.report(DiagnosticCode.DISCOVERY_FOUND, discovered.host)
                    trySend(discovered)
                }
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                diagnostics.report(DiagnosticCode.DISCOVERY_STARTED, serviceType)
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                queue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diagnostics.report(
                    DiagnosticCode.DISCOVERY_UNAVAILABLE,
                    "start failed, code=$errorCode",
                )
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, "stop failed, code=$errorCode")
            }
        }

        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            diagnostics.report(DiagnosticCode.DISCOVERY_UNAVAILABLE, e.javaClass.simpleName)
            close()
            return@callbackFlow
        }

        val timeoutJob = launch {
            delay(timeoutMillis)
            close()
        }

        awaitClose {
            timeoutJob.cancel()
            resolverJob.cancel()
            queue.close()
            runCatching { nsd.stopServiceDiscovery(listener) }
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (e: Exception) {
                // Lock may already have been released by the system.
            }
            diagnostics.report(DiagnosticCode.DISCOVERY_FINISHED)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveOne(nsd: NsdManager, info: NsdServiceInfo): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val results = ArrayList<DiscoveredDevice>(1)
            fun finish() {
                if (finished.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(results)
                }
            }

            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        finish()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (!host.isNullOrBlank()) {
                            results += DiscoveredDevice(
                                name = serviceInfo.serviceName?.take(64) ?: "WLED",
                                host = host,
                                port = serviceInfo.port,
                            )
                        }
                        finish()
                    }
                })
            } catch (cancelled: CancellationException) {
                finished.set(true)
                throw cancelled
            } catch (e: Exception) {
                finish()
            }
        }

    companion object {
        const val SERVICE_TYPE = "_wled._tcp."
    }
}
