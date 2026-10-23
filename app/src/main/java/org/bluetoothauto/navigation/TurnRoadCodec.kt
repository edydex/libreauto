// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded standard OSM node/way payload, shared by network and prepared-area imports. */
internal object TurnRoadCodec {
    const val MAX_BYTES = 3_000_000
    fun decode(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    fun parseGraph(json: String): TurnMatcher.Graph {
        require(json.length <= MAX_BYTES && json.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(json)
        require(root.optString("remark", "").isBlank())
        val rows = root.getJSONArray("elements")
        require(rows.length() <= 36_000)
        val nodes = linkedMapOf<Long, GeoPoint>()
        val ways = arrayListOf<TurnMatcher.Way>()
        val wayIds = hashSetOf<Long>()
        var referenceCount = 0
        for (index in 0 until rows.length()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val row = rows.getJSONObject(index)
            when (row.optString("type")) {
                "node" -> {
                    val id = row.getLong("id")
                    require(!nodes.containsKey(id) && nodes.size < 30_000)
                    nodes[id] = GeoPoint(row.getDouble("lat"), row.getDouble("lon"))
                }
                "way" -> {
                    val id = row.getLong("id")
                    require(wayIds.add(id) && ways.size < 6000)
                    val raw = row.getJSONArray("nodes")
                    require(raw.length() in 2..10_000)
                    referenceCount += raw.length()
                    require(referenceCount <= 60_000)
                    val refs = (0 until raw.length()).map { raw.getLong(it) }
                    val props = row.optJSONObject("tags") ?: JSONObject()
                    require(props.length() <= 256)
                    val tags = linkedMapOf<String, String>()
                    for (key in props.keys()) {
                        val value = props.opt(key)
                        if (key.length <= 100 && value is String && value.length <= 400) tags[key] = value
                    }
                    ways.add(TurnMatcher.Way(id, refs, tags))
                }
            }
        }
        require(ways.all { way -> way.nodes.all { it in nodes } })
        return TurnMatcher.Graph(nodes, ways)
    }
}
