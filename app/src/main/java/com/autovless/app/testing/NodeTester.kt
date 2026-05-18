package com.autovless.app.testing

import com.autovless.app.vless.VlessNode
import java.net.InetSocketAddress
import java.net.Socket

class NodeTester {
    fun quickTcpCheck(node: VlessNode, timeoutMs: Int = 3_000): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(node.server, node.port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
