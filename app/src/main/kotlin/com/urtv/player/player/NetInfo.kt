package com.urtv.player.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** What the system says about the TV's own link (needs only ACCESS_NETWORK_STATE): link speed estimate and Wi-Fi signal. */
object NetInfo {
    data class Link(val kbps: Int, val signalDbm: Int?, val wifi: Boolean)

    fun read(ctx: Context): Link = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Link(0, null, false)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val sig = if (wifi && Build.VERSION.SDK_INT >= 29) caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED } else null
        Link(caps.linkDownstreamBandwidthKbps.coerceAtLeast(0), sig, wifi)
    }.getOrDefault(Link(0, null, false))
}
