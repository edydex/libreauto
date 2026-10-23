// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

/** Retains bounded lightweight inputs, never images. Finished disk-backed items are not regenerated. */
internal class PreparedTripPlan<V : Any>(private val keyOf: (V) -> PreparedTurnKey) {
    private val values = LinkedHashMap<PreparedTurnKey, V>()
    private val inFlight = hashSetOf<PreparedTurnKey>()
    private val complete = hashSetOf<PreparedTurnKey>()
    private val evicted = hashSetOf<PreparedTurnKey>()
    val plannedCount: Int get() = values.size
    val completedCount: Int get() = complete.size
    val pendingCount: Int get() = values.size - complete.size
    val hasWork: Boolean get() = values.keys.any { it !in complete && it !in inFlight && it !in evicted }
    fun contains(key: PreparedTurnKey): Boolean = key in values
    fun value(key: PreparedTurnKey): V? = values[key]

    /** The full proved list replaces obsolete geometry versions while retaining common progress. */
    fun reconcile(items: List<V>): Set<PreparedTurnKey> {
        val incoming = LinkedHashMap<PreparedTurnKey,V>()
        items.take(MAX_TURNS).forEach { incoming.putIfAbsent(keyOf(it),it) }
        val removed = values.keys.filter { it !in incoming }.toSet()
        val retained = incoming.mapValues { (key,value) -> values[key] ?: value }
        values.clear(); values.putAll(retained)
        inFlight.retainAll(values.keys); complete.retainAll(values.keys); evicted.retainAll(values.keys)
        return removed
    }

    /** A progressively proved trip can add steps; only a different trip/reset removes old proof. */
    fun merge(items: List<V>) {
        for (value in items.take(MAX_TURNS)) {
            val key = keyOf(value)
            if (key !in values && values.size < MAX_TURNS) values[key] = value
        }
    }
    fun poll(): V? = values.entries.firstOrNull { it.key !in complete && it.key !in inFlight && it.key !in evicted }
        ?.also { inFlight.add(it.key) }?.value
    fun finish(key: PreparedTurnKey, saved: Boolean) {
        inFlight.remove(key)
        if (saved && key in values) { complete.add(key); evicted.remove(key) }
    }
    /** Budget eviction is reported as incomplete, without an endless render/evict loop. */
    fun forgetSaved(key: PreparedTurnKey) {
        if (key in values) { complete.remove(key); inFlight.remove(key); evicted.add(key) }
    }
    fun clear() { values.clear(); inFlight.clear(); complete.clear(); evicted.clear() }

    companion object { const val MAX_TURNS = 256 }
}
