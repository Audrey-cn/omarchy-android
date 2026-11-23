package com.fct.tc4.acceptance

import com.fct.tc4.Tc4Application
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** Internal device acceptance fixture; inactive unless explicitly enabled in this app's preferences. */
class AcceptanceApplication : Tc4Application() {
    override fun onCreate() {
        super.onCreate()
        if (getSharedPreferences("acceptance_network", MODE_PRIVATE)
                .getInt("github_proxy_port", 0) != 18888) return

        val previous = ProxySelector.getDefault()
        val githubHosts = setOf(
            "github.com", "api.github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com"
        )
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18888))
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> =
                if (uri.host in githubHosts) listOf(proxy)
                else previous?.select(uri) ?: listOf(Proxy.NO_PROXY)

            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
                previous?.connectFailed(uri, sa, ioe)
            }
        })
    }
}
