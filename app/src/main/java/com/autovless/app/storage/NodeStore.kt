package com.autovless.app.storage

import android.content.Context
import com.autovless.app.vless.VlessNode
import com.autovless.app.vless.VlessParser
import org.json.JSONArray
import org.json.JSONObject

class NodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("autovless_nodes", Context.MODE_PRIVATE)

    fun getActiveRaw(): List<String> {
        val json = prefs.getString(KEY_ACTIVE, "[]") ?: "[]"
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                add(array.optString(i))
            }
        }
    }

    fun getActiveNodes(): List<VlessNode> {
        return getActiveRaw().mapNotNull { VlessParser.parseOne(it) }
    }

    fun replaceActive(nodes: List<VlessNode>) {
        val array = JSONArray()
        nodes.forEach { array.put(it.raw) }
        prefs.edit().putString(KEY_ACTIVE, array.toString()).apply()
    }

    fun deleteActiveByKey(nodeKey: String) {
        val filtered = getActiveNodes().filterNot { it.nodeKey == nodeKey }
        replaceActive(filtered)
    }

    fun getInvalidKeys(): Set<String> {
        val json = prefs.getString(KEY_INVALID, "[]") ?: "[]"
        val array = JSONArray(json)
        val keys = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("nodeKey")
            if (key.isNotBlank()) keys += key
        }
        return keys
    }

    fun invalidCount(): Int {
        return getInvalidKeys().size
    }

    fun isInvalid(nodeKey: String): Boolean {
        return nodeKey in getInvalidKeys()
    }

    fun addInvalid(node: VlessNode, reason: String, speedKbps: Double? = null, message: String? = null) {
        val json = prefs.getString(KEY_INVALID, "[]") ?: "[]"
        val array = JSONArray(json)
        val existing = getInvalidKeys()
        if (node.nodeKey in existing) return

        val obj = JSONObject()
            .put("nodeKey", node.nodeKey)
            .put("raw", node.raw)
            .put("reason", reason)
            .put("failedSpeedKbps", speedKbps ?: JSONObject.NULL)
            .put("message", message ?: JSONObject.NULL)
            .put("failedAt", System.currentTimeMillis())
            .put("server", node.server)
            .put("port", node.port)

        array.put(obj)
        prefs.edit().putString(KEY_INVALID, array.toString()).apply()
    }

    fun clearInvalid() {
        prefs.edit().putString(KEY_INVALID, "[]").apply()
    }

    companion object {
        private const val KEY_ACTIVE = "active_nodes"
        private const val KEY_INVALID = "invalid_nodes"
    }
}
