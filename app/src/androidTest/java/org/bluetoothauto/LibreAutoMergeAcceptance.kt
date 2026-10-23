// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.bluetoothauto.integration.CarDisplayController
import org.bluetoothauto.integration.CarDisplayMode
import org.bluetoothauto.integration.OfficialAutoController
import org.bluetoothauto.navigation.OwnDestinations
import org.bluetoothauto.navigation.OwnNavigation
import org.openandroidauto.ProjectionService
import org.openandroidauto.ServiceState
import org.openandroidauto.integration.ProjectionAvailability
import org.openandroidauto.integration.ProjectionIntegration
import org.openandroidauto.integration.SharedLibraryIntegration
import org.openandroidauto.media.MediaLibraryStore
import org.openandroidauto.navigation.DestinationPreferences
import org.openandroidauto.render.FrameCadenceCounters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real host/services; no projection sockets, routes, user-media playback or helper transactions.
 * Run only while idle. Every touched preference is restored and URI grants are read-only. */
object LibreAutoMergeAcceptance {
    fun run(test: Instrumentation, identityCheck: Boolean = false): Bundle {
        val app = test.targetContext
        val playerPrefs = app.getSharedPreferences("bluetoothauto", Context.MODE_PRIVATE)
        val connectionPrefs = app.getSharedPreferences("libreauto_connection", Context.MODE_PRIVATE)
        val officialPrefs = app.getSharedPreferences("libreauto_official_auto", Context.MODE_PRIVATE)
        val destinationPrefs = app.getSharedPreferences("own-map-destinations", Context.MODE_PRIVATE)
        val initialPlayerPrefs = playerPrefs.all.toMap()
        val initialConnectionPrefs = connectionPrefs.all.toMap()
        val initialOfficialPrefs = officialPrefs.all.toMap()
        val initialDestinations = destinationPrefs.all.toMap()
        val initialGrants = grants(app)
        val initialAa = officialPackageState(app)
        val initialMode = CarDisplayController.mode(app)
        val initialAlias = app.packageManager.getComponentEnabledSetting(
            ComponentName(app.packageName, "org.openandroidauto.UsbProjectionEntry"))
        val frameBaseline = FrameCadenceCounters.currentSession().snapshot()
        val checks = mutableListOf<String>()
        var assertions = 0
        fun verify(value: Boolean, message: String) { assertions++; check(value) { message } }
        fun onMain(action: () -> Unit) = test.runOnMainSync(action)
        fun await(label: String, timeout: Long = 10_000, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < until) {
                var ready = false
                onMain { ready = predicate() }
                if (ready) return
                SystemClock.sleep(30)
            }
            error("LibreAuto acceptance timed out: $label")
        }
        fun select(mode: CarDisplayMode): CarDisplayController.Result {
            val done = CountDownLatch(1)
            var result: CarDisplayController.Result? = null
            onMain { CarDisplayController.selectMode(app, mode, LibreAutoRuntime) { result = it; done.countDown() } }
            check(done.await(24, TimeUnit.SECONDS)) { "Car display transition did not complete" }
            return checkNotNull(result).also {
                verify(it.status !in setOf(CarDisplayController.Status.FAILED, CarDisplayController.Status.BUSY), "Car display transition failed: ${it.message}")
                verify(it.mode == mode, "Car display mode was not persisted")
            }
        }
        var activity: Activity? = null
        var baselineState: (() -> Unit)? = null
        var changedPreferences = false
        try {
            onMain {
                verify(!AppState.running && !AppState.playback.playing, "Stop music before running the merge acceptance")
                verify(!ProjectionIntegration.isSessionActive() && !ServiceState.projectionActive, "Stop projection before running the merge acceptance")
                verify(!OwnNavigation.active && GuidanceHub.current == null, "Stop navigation before running the merge acceptance")
                verify(!CarDisplayController.isSwitching(), "Wait for the car display change to finish")
                verify(initialMode == CarDisplayMode.BLUETOOTH, "Select Bluetooth stereo before running the merge acceptance")
                // A pending journal must be recovered by the real UI, never bypassed by a test.
                verify(!officialPrefs.contains("original_enabled_state") && !officialPrefs.contains("applied_enabled_state"),
                    "Resolve the existing Android Auto restore record before running this test")
                baselineState = captureIdleState()
                verify(ProjectionIntegration.delegate != null, "Host projection delegate was not installed")
                verify(SharedLibraryIntegration.delegate != null, "Shared library delegate was not installed")
                verify(!SharedLibraryIntegration.playbackReady(), "An idle phone retained a projected decoder")
                verify(!ProjectionIntegration.isProjectionSelected(), "Bluetooth mode selected the projector")
            }
            checks += "idle host adapters installed; projector and projected media owner absent"

            val hostQueue = Library.tracks(app).map { it.uri }
            val projectionQueue = MediaLibraryStore.read(app).map { it.toString() }
            verify(hostQueue.groupingBy { it }.eachCount() == projectionQueue.groupingBy { it }.eachCount(),
                "Projected library changed queue entries or duplicate occurrences")
            if (!Library.shuffle(app)) verify(hostQueue == projectionQueue, "Projected library changed the ordered queue")
            val saved = OwnDestinations.saved(app)
            val recent = OwnDestinations.recent(app)
            val projectedPlaces = DestinationPreferences.open(app).snapshot()
            fun samePlaces(host: List<org.bluetoothauto.navigation.OwnMapProvider.Place>, projected: List<org.openandroidauto.navigation.DestinationStore.Place>) =
                host.size == projected.size && host.indices.all { i ->
                    host[i].name == projected[i].name &&
                        kotlin.math.abs(host[i].point.latitude - projected[i].latitude) < .0000001 &&
                        kotlin.math.abs(host[i].point.longitude - projected[i].longitude) < .0000001
                }
            verify(samePlaces(saved, projectedPlaces.saved), "Projection saved places differ from the phone map")
            verify(samePlaces(recent, projectedPlaces.recent), "Projection recent places differ from the phone map")
            verify(destinationPrefs.all == initialDestinations, "Reading shared places wrote a preference")
            verify(playerPrefs.all == initialPlayerPrefs, "Reading the shared queue changed player settings or playlists")
            checks += "${hostQueue.size} retained queue entries; shared saved/recent places read without writes"

            // Silence map/cue paths for the temporary paused owner. No user media is opened.
            changedPreferences = true
            onMain {
                check(playerPrefs.edit().putBoolean("map", false).putString("audioMode", "OFF").commit())
                check(officialPrefs.edit().putBoolean("automatic_switching", false).commit())
                verify(CarDisplayController.recordPreferredProjector(app, CarDisplayMode.LIBREAUTO), "Could not record projector preference")
                verify(CarDisplayController.preferredProjector(app) == CarDisplayMode.LIBREAUTO, "Preferred projector was not saved")
                verify(!CarDisplayController.recordPreferredProjector(app, CarDisplayMode.BLUETOOTH), "Bluetooth override replaced projector preference")
            }
            activity = test.startActivitySync(Intent(app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            test.waitForIdleSync()
            SystemClock.sleep(250)
            verify(!ProjectionIntegration.isSessionActive() && !ServiceState.projectionActive,
                "Opening the music player started projection")
            checks += "music launcher opens without starting projection"

            onMain { BluetoothAutoService.command(app, BluetoothAutoService.PAUSE) }
            await("temporary paused VLC owner") { AppState.running }
            onMain { verify(!AppState.playback.playing, "Paused acceptance owner played user media") }
            select(CarDisplayMode.LIBREAUTO)
            onMain {
                verify(!AppState.running, "Mode transition did not release VLC")
                verify(!ProjectionIntegration.isSessionActive() && !ServiceState.projectionActive,
                    "Selecting LibreAuto started projection eagerly")
                verify(ProjectionIntegration.isProjectionSelected() == ProjectionAvailability.isSupported(), "Projection selection ignored Android support")
            }
            checks += "actual mode transition releases paused VLC; selection starts no projection"

            if (ProjectionAvailability.isSupported()) {
                // Selected mode alone permits phone playback. A real connection must prepare a handoff.
                onMain { BluetoothAutoService.command(app, BluetoothAutoService.PAUSE) }
                await("second paused VLC owner") { AppState.running }
                onMain { verify(!ProjectionIntegration.isProjectionAllowed(), "Projection could race the existing VLC owner") }
                val ready = CountDownLatch(1)
                var handoff = false
                onMain { ProjectionIntegration.prepareToStart(app) { handoff = it; ready.countDown() } }
                verify(ready.await(12, TimeUnit.SECONDS), "Projection preparation callback timed out")
                onMain {
                    verify(handoff && !AppState.running && ProjectionIntegration.isProjectionAllowed(), "Preparation did not join/release VLC")
                    verify(!ProjectionIntegration.isSessionActive(), "Preparing a connection started it")
                }
                checks += "real prepareToStart handoff releases VLC before allowing projection"
            } else checks += "Android 8–12 guard: player works, projection stays unavailable"

            select(CarDisplayMode.OFFICIAL_AA)
            verify(!ProjectionIntegration.isProjectionSelected(), "Official Android Auto mode left LibreAuto selected")
            verify(officialPackageState(app) == initialAa, "Manual mode selection changed official Android Auto")
            select(CarDisplayMode.BLUETOOTH)
            verify(CarDisplayController.preferredProjector(app) == CarDisplayMode.LIBREAUTO,
                "Temporary car mode changes overwrote the preferred projector")
            checks += "preferred LibreAuto projector survives official/Bluetooth car overrides"
            onMain {
                app.startForegroundService(Intent(app, ProjectionService::class.java))
            }
            await("rejected projection service destruction") { !serviceRunning(app, ProjectionService::class.java.name) }
            SystemClock.sleep(200)
            onMain {
                verify(!ProjectionIntegration.isSessionActive() && !ServiceState.projectionActive, "A stale service start acquired projection")
                verify(!SharedLibraryIntegration.playbackReady(), "A rejected start created a projected decoder")
                verify(!AppState.running, "A rejected projection start launched the phone player")
            }
            val frameAfter = FrameCadenceCounters.currentSession().snapshot()
            for (key in listOf("frame_epoch", "encoder_streams_started", "encoded_output_buffers", "map_rendered_frames"))
                verify(frameAfter[key] == frameBaseline[key], "A rejected start changed projection counter $key")
            verify(!Regex("(?m)^\\s*\\w+_WAKE_LOCK.*OpenAuto:Session").containsMatchIn(shell(test, "dumpsys power")),
                "A rejected start retained the projection wake lock")
            verify(Thread.getAllStackTraces().keys.none { thread ->
                thread.isAlive && listOf("OpenAuto-Encoder", "OpenAuto-PCM", "OpenAuto-CarBluetooth").any { prefix -> thread.name.startsWith(prefix) }
            }, "A rejected start created a projection worker")
            checks += "direct stale service start rejected; no projector, decoder, encoder frames, worker or wake lock"

            if (identityCheck) {
                verify(ProjectionAvailability.isSupported(), "Installed-AA diagnostic requires Android 12L+")
                // This uses the same bounded, local-only check as the explicit setup button.
                // The check closes material before returning; only its status name is reported.
                val readerClass = Class.forName("org.openandroidauto.identity.InstalledAaIdentityReader")
                val stopClass = Class.forName("org.openandroidauto.identity.ProjectionStopSignal")
                val reader = readerClass.getField("INSTANCE").get(null)
                val stop = stopClass.getDeclaredConstructor().newInstance()
                val result = readerClass.getMethod("check", Context::class.java, stopClass).invoke(reader, app, stop).toString()
                verify(result == "READY", "Installed Android Auto compatibility check: $result")
                checks += "installed Android Auto compatibility verified locally; no credentials written/reported"
            }
            verify(grants(app) == initialGrants, "Merge checks changed persisted URI grants")
            verify(officialPackageState(app) == initialAa, "Merge checks changed official Android Auto state")
            verify(destinationPrefs.all == initialDestinations, "Merge checks changed saved places")
        } finally {
            if (changedPreferences) {
                onMain {
                    app.stopService(Intent(app, BluetoothAutoService::class.java))
                    ProjectionIntegration.requestStop(app)
                }
                try {
                    await("acceptance cleanup") { !AppState.running && !ProjectionIntegration.isSessionActive() }
                } finally {
                    onMain {
                        restoreKeys(playerPrefs, initialPlayerPrefs, setOf("map", "audioMode"))
                        restoreKeys(connectionPrefs, initialConnectionPrefs, setOf("car_display_mode", "preferred_projection_mode"))
                        restoreKeys(officialPrefs, initialOfficialPrefs, setOf("automatic_switching"))
                        ProjectionIntegration.syncUsbEntry(app)
                        // Preserve the exact pre-test component override, including DEFAULT.
                        app.packageManager.setComponentEnabledSetting(ComponentName(app.packageName, "org.openandroidauto.UsbProjectionEntry"),
                            initialAlias, android.content.pm.PackageManager.DONT_KILL_APP)
                        if (!AppState.running && !ProjectionIntegration.isSessionActive()) baselineState?.invoke()
                        activity?.finish()
                    }
                }
            }
        }
        verify(playerPrefs.all == initialPlayerPrefs, "Player preferences or playlists were not restored")
        verify(connectionPrefs.all == initialConnectionPrefs, "Car display preference was not restored")
        verify(officialPrefs.all == initialOfficialPrefs, "Automatic-switching preference or restore journal changed")
        verify(destinationPrefs.all == initialDestinations && grants(app) == initialGrants, "Places or media permissions changed")
        checks += "original mode, player settings, helper settings, playlists, places and URI grants preserved"
        return Bundle().apply {
            putInt("libreauto_merge_assertions", assertions)
            putString("libreauto_merge", checks.joinToString("; "))
            putInt("retained_queue_entries", Library.tracks(app).size)
        }
    }

    private fun grants(context: Context) = context.contentResolver.persistedUriPermissions.map {
        Triple(it.uri.toString(), it.isReadPermission, it.isWritePermission)
    }.sortedBy { it.first }

    @Suppress("DEPRECATION")
    private fun serviceRunning(context: Context, name: String): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE).any {
            it.service.packageName == context.packageName && it.service.className == name
        }

    private fun officialPackageState(context: Context): Pair<Int, Boolean>? = try {
        val name = OfficialAutoController.ANDROID_AUTO_PACKAGE
        context.packageManager.getApplicationEnabledSetting(name) to context.packageManager.getApplicationInfo(name, 0).enabled
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
      catch (_: IllegalArgumentException) { null }

    private fun shell(test: Instrumentation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText().take(262_144) }

    private fun restoreKeys(prefs: SharedPreferences, baseline: Map<String, *>, keys: Set<String>) {
        val edit = prefs.edit()
        for (key in keys) when (val value = baseline[key]) {
            null -> edit.remove(key)
            is Boolean -> edit.putBoolean(key, value)
            is String -> edit.putString(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            else -> error("Unsupported acceptance preference type")
        }
        check(edit.commit()) { "Could not restore acceptance preferences" }
    }

    private fun captureIdleState(): () -> Unit {
        val playback = AppState.playback
        val presentation = AppState.presentation
        val guidance = AppState.guidance
        val position = AppState.position
        val artwork = AppState.artwork
        val geometry = AppState.turnGeometry
        val artStatus = AppState.artworkStatus
        val title = AppState.metadataTitle
        val demo = AppState.demo
        val error = AppState.error
        return {
            AppState.playback = playback; AppState.presentation = presentation
            AppState.guidance = guidance; AppState.position = position
            AppState.artwork = artwork?.takeUnless { it.isRecycled }; AppState.turnGeometry = geometry
            AppState.artworkStatus = artStatus; AppState.metadataTitle = title
            AppState.demo = demo; AppState.error = error; AppState.changed()
        }
    }
}
