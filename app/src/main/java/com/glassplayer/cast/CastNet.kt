package com.glassplayer.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** 投屏场景的网络信息：只读公开 API，不需要定位权限。 */
object CastNet {

    /** 手机在局域网里的 IPv4（优先 site-local，即 192.168 / 10. / 172. 段）。 */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** 一行网络摘要，写进日志给排障用。 */
    fun summary(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val ip = localIpv4()
        return "网络=${if (isWifi) "Wi-Fi" else "非Wi-Fi"} 联网=$online 本机IP=${ip ?: "无"}"
    }
}
