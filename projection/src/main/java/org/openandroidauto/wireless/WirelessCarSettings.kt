package org.openandroidauto.wireless

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import org.openandroidauto.bluetooth.PairedCarAddress
import java.io.Closeable

/** Explicit paired-car selection only. Refresh never scans, pairs, enables a radio or joins Wi-Fi. */
class WirelessCarSettings(
    private val activity: Activity,
    private val onConnect: (String) -> Unit,
    private val onStop: () -> Unit
) : Closeable {
    private data class Car(val address: String, val name: String)
    private val main = Handler(Looper.getMainLooper())
    private val preferences = activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val status = TextView(activity).apply { textSize = 15f; setPadding(0, 8, 0, 8) }
    private val cars = Spinner(activity)
    private val refreshButton = Button(activity).apply { text = "Refresh paired cars"; setOnClickListener { refresh() } }
    private val connectButton = Button(activity).apply {
        text = "Connect to selected car"
        setOnClickListener {
            val chosen = selectedCar() ?: return@setOnClickListener
            val stillPaired = try {
                // Recheck bonding and permission at the user's action, not only at the last Refresh.
                pairedCars().any { it.address == chosen.address }
            } catch (_: SecurityException) {
                setStatus("Allow Nearby devices access in phone settings, then Refresh.")
                return@setOnClickListener
            } catch (_: IllegalStateException) {
                setStatus("Turn on Bluetooth in phone settings, then Refresh.")
                return@setOnClickListener
            }
            if (!stillPaired) { refresh(); setStatus("That car is no longer paired. Choose a paired car."); return@setOnClickListener }
            onConnect(chosen.address)
        }
    }
    private val forgetButton = Button(activity).apply {
        text = "Forget car selection"
        setOnClickListener {
            onStop()
            preferences.edit().remove(ADDRESS).remove(NAME).apply()
            refresh()
            setStatus("Car selection forgotten. The phone's Bluetooth pairing is unchanged.")
        }
    }
    private val stopButton = Button(activity).apply { text = "Stop wireless connection"; setOnClickListener { onStop() } }
    private var choices: List<Car> = emptyList()
    private var refreshing = false
    private var connecting = false
    private var closed = false

    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 20, 0, 12)
        addView(TextView(activity).apply { text = "Wireless car connection · experimental"; textSize = 20f })
        addView(TextView(activity).apply {
            text = "Pair your car in phone Bluetooth settings, then choose it here. Connect starts only when you press it. Your first Wi-Fi connection may ask for approval on the phone. Car compatibility is still being tested."
            textSize = 15f; setPadding(0, 8, 0, 8)
        })
        addView(cars); addView(refreshButton); addView(connectButton); addView(forgetButton); addView(stopButton); addView(status)
    }

    init {
        cars.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (closed || refreshing || position <= 0) return
                val car = choices.getOrNull(position - 1) ?: return
                preferences.edit().putString(ADDRESS, car.address).putString(NAME, car.name).apply()
                updateControls()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        refresh()
    }

    fun refresh() = onMain {
        if (connecting) return@onMain
        refreshing = true
        try {
            choices = pairedCars()
            val saved = preferences.getString(ADDRESS, null)
            val selected = choices.indexOfFirst { it.address == saved }
            val labels = listOf("Choose your paired car") + choices.map { it.name }
            cars.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, labels)
            cars.setSelection(selected + 1)
            status.text = when {
                choices.isEmpty() -> "No paired cars found. Pair your car in phone Bluetooth settings, then Refresh."
                saved != null && selected < 0 -> "The saved car is not currently paired. Choose a paired car or forget the selection."
                selected >= 0 -> "Paired car selected. Wireless and certificate compatibility are experimental."
                else -> "Choose the car you paired in phone settings."
            }
        } catch (_: SecurityException) {
            clearVisibleChoices()
            status.text = "Allow Nearby devices access in phone settings, then Refresh."
        } catch (_: IllegalStateException) {
            clearVisibleChoices()
            status.text = "Turn on Bluetooth in phone settings, then Refresh."
        } finally { refreshing = false; updateControls() }
    }

    fun setConnecting(value: Boolean) = onMain { connecting = value; updateControls() }

    /** Supply fixed nonpersonal connection-stage text, never a device address or Wi-Fi credential. */
    fun setStatus(message: String) = onMain { status.text = message }

    private fun pairedCars(): List<Car> {
        if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException()
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IllegalStateException()
        if (!adapter.isEnabled) throw IllegalStateException()
        return adapter.bondedDevices.filter { it.bondState == BluetoothDevice.BOND_BONDED }.mapNotNull { device ->
            val address = try { PairedCarAddress.normalize(device.address) } catch (_: IllegalArgumentException) { return@mapNotNull null }
            Car(address, device.name?.takeIf { it.isNotBlank() } ?: "Unnamed paired device")
        }.sortedWith(compareBy<Car> { it.name.lowercase() }.thenBy { it.address })
    }

    private fun selectedCar(): Car? = choices.getOrNull(cars.selectedItemPosition - 1)
    private fun clearVisibleChoices() {
        choices = emptyList()
        cars.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("Choose your paired car"))
    }
    private fun updateControls() {
        cars.isEnabled = !connecting && choices.isNotEmpty()
        refreshButton.isEnabled = !connecting
        connectButton.isEnabled = !connecting && selectedCar() != null
        forgetButton.isEnabled = !connecting && preferences.contains(ADDRESS)
        stopButton.isEnabled = true
    }
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) main.post { if (!closed) action() }
        else if (!closed) action()
    }
    override fun close() { closed = true; main.removeCallbacksAndMessages(null) }

    companion object {
        private const val PREFERENCES = "open_auto_wireless"
        private const val ADDRESS = "selected_car_address"
        private const val NAME = "selected_car_name"
    }
}
