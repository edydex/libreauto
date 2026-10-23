// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

/** Geometry identity dominates changing GPS/camera inputs; units and style revisions isolate renders. */
internal data class PreparedTurnKey(val geometry: String, val units: String, val styleRevision: String) {
    init { require(geometry.isNotBlank() && geometry.length <= 256); require(units.length <= 32); require(styleRevision.length <= 256) }
}

/** Owns at most three independent images. Taking an image transfers ownership without disposing it. */
internal class PreparedTurnCache<V : Any>(private val maximum: Int = 3, private val dispose: (V) -> Unit) {
    private val values = LinkedHashMap<PreparedTurnKey, V>(maximum, .75f, true)
    init { require(maximum in 1..3) }
    val size: Int get() = values.size
    fun peek(key: PreparedTurnKey): V? = values[key]
    fun take(key: PreparedTurnKey): V? = values.remove(key)
    fun put(key: PreparedTurnKey, value: V) {
        require(values.entries.none { it.key != key && it.value === value }) { "Cached images must have one owner" }
        values.put(key, value)?.takeIf { it !== value }?.let(dispose)
        while (values.size > maximum) {
            val oldest = values.entries.iterator().next()
            values.remove(oldest.key)?.let(dispose)
        }
    }
    fun clear() {
        val removed = values.values.toList()
        values.clear()
        removed.forEach(dispose)
    }
}

/** Pending future work has a separate fixed bound; earliest lookahead retains its priority. */
internal class PreparedTurnWorkQueue<V : Any>(private val keyOf: (V) -> PreparedTurnKey) {
    private val values = java.util.ArrayDeque<V>()
    val size: Int get() = values.size
    fun isEmpty() = values.isEmpty()
    fun isNotEmpty() = values.isNotEmpty()
    fun find(key: PreparedTurnKey): V? = values.firstOrNull { keyOf(it) == key }
    fun offer(value: V, first: Boolean = false): Boolean {
        if (values.size >= 3 || find(keyOf(value)) != null) return false
        if (first) values.addFirst(value) else values.addLast(value)
        return true
    }
    fun take(key: PreparedTurnKey): V? = find(key)?.also { values.remove(it) }
    fun removeKeys(keys: Set<PreparedTurnKey>): List<V> = values.filter { keyOf(it) in keys }
        .also { removed -> removed.forEach { values.remove(it) } }
    fun removeMatching(matches: (V) -> Boolean): List<V> = values.filter(matches)
        .also { removed -> removed.forEach { values.remove(it) } }
    fun poll(): V? = values.pollFirst()
    fun clearAndTake(): List<V> = values.toList().also { values.clear() }
}
