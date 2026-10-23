// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.integration

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class CarProfile(val address: String, val name: String, val mode: CarDisplayMode)

object CarProfileDefaults {
    /** Bluetooth-only is an explicit per-car override, never a new car's assumed capability. */
    fun preferredProjection(current: CarDisplayMode, sdkInt: Int): CarDisplayMode =
        if (current == CarDisplayMode.LIBREAUTO && sdkInt >= 32) CarDisplayMode.LIBREAUTO else CarDisplayMode.OFFICIAL_AA
}

/** Broadcast lifetime and later asynchronous callbacks share one durable cancellation bit. */
class CarProfileEvent {
    private val cancelled = AtomicBoolean()
    internal var ticket = -1L
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel(): Boolean = cancelled.compareAndSet(false, true)
    internal fun canContinue(currentTicket: Long): Boolean = !isCancelled && ticket >= 0 && ticket == currentTicket
}

object CarProfilesPermissions {
    fun required(sdkInt: Int, hasProjection: Boolean): List<String> = buildList {
        add(if (sdkInt >= 31) "android.permission.BLUETOOTH_CONNECT" else "android.permission.BLUETOOTH")
        if (sdkInt >= 33) add("android.permission.POST_NOTIFICATIONS")
        if (hasProjection && sdkInt >= 32) {
            add("android.permission.ACCESS_FINE_LOCATION")
            if (sdkInt >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
        }
    }
}

object CarProfilesCodec {
    const val MAX_PROFILES = 16
    fun address(raw: String?): String? = raw?.uppercase(Locale.ROOT)?.takeIf {
        Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}").matches(it)
    }
    fun decode(raw: String?): List<CarProfile> {
        if (raw == null) return emptyList()
        require(raw.length <= 32_768) { "Saved car profiles are too large" }
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "Unsupported car profile format" }
        val rows = root.getJSONArray("cars")
        require(rows.length() <= MAX_PROFILES) { "Too many car profiles" }
        val result = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val pin = requireNotNull(address(row.getString("address"))) { "Invalid paired car" }
            val name = row.getString("name")
            require(name.isNotBlank() && name.length <= 80 && name.none(Char::isISOControl)) { "Invalid car name" }
            val mode = CarDisplayMode.entries.firstOrNull { it.name == row.getString("mode") }
            requireNotNull(mode) { "Invalid car display choice" }
            CarProfile(pin, name, mode)
        }
        require(result.map { it.address }.distinct().size == result.size) { "Repeated paired car" }
        return result
    }
    fun encode(profiles: List<CarProfile>): String {
        val rows = JSONArray()
        profiles.forEach { rows.put(JSONObject().put("address", it.address).put("name", it.name).put("mode", it.mode.name)) }
        return JSONObject().put("version", 1).put("cars", rows).toString().also { decode(it) }
    }
}

/** Exact selected devices only. Repeated ACL broadcasts cannot override a manual choice. */
class CarProfilesPolicy {
    enum class Action { IGNORE, APPLY, AMBIGUOUS, DISCONNECTED }
    data class Decision(val action: Action, val address: String? = null)
    private val connected = linkedSetOf<String>()
    private var active: String? = null
    private var paused = false
    fun connect(address: String, known: Set<String>): Decision {
        if (address !in known || !connected.add(address)) return Decision(Action.IGNORE)
        if (connected.size != 1) { active = null; paused = true; return Decision(Action.AMBIGUOUS) }
        active = address; paused = false
        return Decision(Action.APPLY, address)
    }
    fun disconnect(address: String): Decision {
        if (!connected.remove(address)) return Decision(Action.IGNORE)
        if (active == address) active = null
        if (connected.isEmpty()) paused = false
        return Decision(Action.DISCONNECTED, address)
    }
    fun manualSelection() { paused = true }
    fun canApply(address: String) = !paused && active == address && connected.size == 1
    fun clear() { connected.clear(); active = null; paused = false }
    fun save(): String = JSONObject().put("connected", JSONArray(connected.toList()))
        .put("active", active ?: JSONObject.NULL).put("paused", paused).toString()
    fun restore(raw: String?, known: Set<String>) {
        clear()
        if (raw.isNullOrBlank()) return
        runCatching {
            require(raw.length <= 4096)
            val root = JSONObject(raw)
            val list = root.getJSONArray("connected")
            require(list.length() <= CarProfilesCodec.MAX_PROFILES)
            for (i in 0 until list.length()) list.getString(i).takeIf { it in known }?.let(connected::add)
            active = root.optString("active").takeIf { it in connected && connected.size == 1 }
            paused = root.optBoolean("paused", false) || connected.size > 1
        }.onFailure { clear() }
    }
}

/** A start request is never readiness. Only exact service metadata can establish ownership. */
class CarProfileAttempt {
    var pending: String? = null; private set
    var owned: String? = null; private set
    private var ready = false
    fun request(address: String) { pending = address; owned = null; ready = false }
    fun carSelected(address: String?, eligible: Boolean) {
        owned = address?.takeIf { eligible && it == pending }
        pending = null; ready = false
    }
    fun becameReady() { if (owned != null) ready = true }
    fun stopped(): String? {
        val failed = (pending ?: owned).takeIf { !ready }
        clear(); return failed
    }
    fun disconnected(address: String): Boolean {
        val stop = owned == address
        if (pending == address || stop) clear()
        return stop
    }
    fun clear() { pending = null; owned = null; ready = false }
}
