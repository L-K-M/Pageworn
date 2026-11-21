package ch.lkmc.pageworn.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Whether an address is on what Android 17 classes as the local network, which needs the
 * ACCESS_LOCAL_NETWORK runtime permission for an app targeting SDK 37. Private IPv4 ranges,
 * link-local and carrier-grade NAT (100.64.0.0/10, which Tailscale uses), IPv6 unique-local and
 * link-local addresses, and multicast DNS names count as local. Loopback is the phone itself.
 * Whether a VPN route (Tailscale) is exempt in practice is an owner check on the phone; the
 * classification errs toward asking, because a blocked connection otherwise looks like a timeout.
 */
object LocalNetworkClass {
    fun isLocalHost(host: String): Boolean = host.lowercase().trimEnd('.').endsWith(".local")

    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return false
        if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return true
        val b = address.address
        return when (address) {
            is Inet4Address -> (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            is Inet6Address -> (b[0].toInt() and 0xfe) == 0xfc
            else -> false
        }
    }
}

/** The platform side of the local-network rule: does it apply, is it granted, is this address local. */
class LocalNetworkAccess(
    private val context: Context,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Android 14 to 16 have no such permission and leave local access unrestricted. */
    val applies: Boolean get() = Build.VERSION.SDK_INT >= ANDROID_17

    fun granted(): Boolean = !applies || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when an address on [host] needs the permission. A name that does not resolve is not local. */
    suspend fun isLocal(host: String): Boolean {
        if (LocalNetworkClass.isLocalHost(host)) return true
        val addresses = withContext(Dispatchers.IO) { runCatching { resolve(host) }.getOrDefault(emptyList()) }
        return addresses.any(LocalNetworkClass::isLocalAddress)
    }

    /** True when Android would block a connection to [host] right now. */
    suspend fun blocks(host: String): Boolean = applies && !granted() && isLocal(host)

    companion object {
        const val ANDROID_17 = 37
        const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}
