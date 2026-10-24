package com.eloykim.lyra.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** 当前网络连接快照；只读、由 [NetworkStateReader] 生成。 */
data class NetworkState(
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val transports: List<String>,
    val interfaceName: String?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val dns: List<String>,
    val wifiEnabled: Boolean,
    val wifiConnected: Boolean,
    val cellularConnected: Boolean,
    val vpnConnected: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("connected", connected)
        .put("validated", validated)
        .put("metered", metered)
        .put("transports", JSONArray(transports))
        .put("interface", interfaceName)
        .put("ipv4", JSONArray(ipv4))
        .put("ipv6", JSONArray(ipv6))
        .put("dns", JSONArray(dns))
        .put("wifiEnabled", wifiEnabled)
        .put("wifiConnected", wifiConnected)
        .put("cellularConnected", cellularConnected)
        .put("vpnConnected", vpnConnected)
}

/**
 * 把 ConnectivityManager / WifiManager 的状态整理成一份只读快照。
 * 不负责开关网络，开关动作在 NetworkService 里。
 */
class NetworkStateReader(private val context: Context) {

    fun read(): NetworkState {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val wifi = context.getSystemService(WifiManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val link = network?.let { connectivity.getLinkProperties(it) }
        val transports = buildList {
            if (capabilities == null) return@buildList
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
        val addresses = link?.linkAddresses.orEmpty()
        return NetworkState(
            connected = capabilities != null,
            validated = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
            ) ?: false,
            metered = connectivity?.isActiveNetworkMetered ?: false,
            transports = transports,
            interfaceName = link?.interfaceName,
            ipv4 = addresses.filter { it.address is java.net.Inet4Address }
                .mapNotNull { it.address.hostAddress },
            ipv6 = addresses.filter { it.address is java.net.Inet6Address }
                .mapNotNull { it.address.hostAddress },
            dns = link?.dnsServers.orEmpty()
                .mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() },
            wifiEnabled = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false),
            wifiConnected = "wifi" in transports,
            cellularConnected = "cellular" in transports,
            vpnConnected = "vpn" in transports,
        )
    }

    /** 打开系统的网络/Wi-Fi 设置面板，让用户自己完成开关。 */
    fun openWifiPanel(): Boolean = openPanel("android.settings.panel.action.WIFI")

    fun openInternetPanel(): Boolean = openPanel("android.settings.panel.action.INTERNET_CONNECTIVITY")

    private fun openPanel(action: String): Boolean = runCatching {
        val intent = android.content.Intent(action)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
