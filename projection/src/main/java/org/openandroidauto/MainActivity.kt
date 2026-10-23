package org.openandroidauto

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.openandroidauto.calls.CallsAccess
import org.openandroidauto.identity.InstalledAaIdentitySettings
import org.openandroidauto.integration.ProjectionAvailability
import org.openandroidauto.integration.ProjectionIntegration
import org.openandroidauto.media.AudioLibraryActivity
import org.openandroidauto.notifications.NotificationSettingsView
import org.openandroidauto.notifications.NotificationSources
import org.openandroidauto.phonesetup.PhoneSetupState
import org.openandroidauto.phonesetup.PhoneSetupState.Screen
import org.openandroidauto.phonesetup.PhoneSetupUi
import org.openandroidauto.sms.SmsAccess
import org.openandroidauto.ui.CarUi
import org.openandroidauto.wireless.WirelessCarSettings
import org.openandroidauto.wireless.WirelessConnectionReport

/** Phone setup only. Navigation intents never connect or enable optional features. */
class MainActivity : Activity() {
    companion object {
        const val EXTRA_SETUP_SCREEN = "org.openandroidauto.extra.SETUP_SCREEN"
        private const val CORE_PERMISSIONS_REQUEST = 31
        private const val WIRELESS_PERMISSIONS_REQUEST = 32
        private const val SAVED_SCREEN = "phone-setup-screen"
        private const val SAVED_PORT = "phone-setup-port"
        private const val SAVED_PERMISSION_PENDING = "core-permission-pending"
    }
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private lateinit var ui: PhoneSetupUi
    private var screen = Screen.HOME
    private var portValue = "5288"
    private var port: EditText? = null
    private var connectionTitle: TextView? = null
    private var connectionDetail: TextView? = null
    private var homeAction: Button? = null
    private var startButton: Button? = null
    private var stopButton: Button? = null
    private var eventsView: TextView? = null
    private var voiceStatus: TextView? = null
    private var voiceSetup: Button? = null
    private var callsStatus: TextView? = null
    private var smsStatus: TextView? = null
    private val summaries = mutableMapOf<Screen, TextView>()
    private var installedIdentitySettings: InstalledAaIdentitySettings? = null
    private var wirelessSettings: WirelessCarSettings? = null
    private var messageSettings: NotificationSettingsView? = null
    private var pendingWirelessAddress: String? = null
    private var wirelessPermissionPending = false
    private var corePermissionPending = false
    private var stopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!allowProjectionEntry()) return
        ServiceState.init(this)
        portValue = savedInstanceState?.getString(SAVED_PORT)?.takeIf { it.length <= 5 } ?: "5288"
        corePermissionPending = savedInstanceState?.getBoolean(SAVED_PERMISSION_PENDING) ?: false
        ui = PhoneSetupUi(this) { showScreen(Screen.HOME) }
        setContentView(ui.root)
        val initial = savedInstanceState?.getString(SAVED_SCREEN)?.let(Screen::fromKey)
            ?: if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_ACCESSORY_ATTACHED) Screen.CONNECTION
            else Screen.fromKey(intent?.getStringExtra(EXTRA_SETUP_SCREEN))
        showScreen(initial)
        // Preserve the existing first-run path. Calls/SMS still need their separate explicit opt-ins.
        requestNeededPermissions()
        if (PhoneSetupState.autoStartUsb(intent?.action, savedInstanceState != null)) startProjection()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!allowProjectionEntry()) return
        val usb = PhoneSetupState.autoStartUsb(intent.action, false)
        showScreen(if (usb) Screen.CONNECTION else Screen.fromKey(intent.getStringExtra(EXTRA_SETUP_SCREEN)))
        if (usb) startProjection()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_SCREEN, screen.key)
        outState.putString(SAVED_PORT, portValue)
        outState.putBoolean(SAVED_PERMISSION_PENDING, corePermissionPending)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (!::ui.isInitialized || !allowProjectionEntry()) return
        refreshSetup()
        observation?.cancel()
        observation = uiScope.launch {
            // State and the volatile session-owner flag are published in the same Main turn.
            // Read after that turn, including the final DISCONNECTED -> inactive transition.
            launch { ServiceState.connectionState.collect { yield(); refreshConnection() } }
            launch { ServiceState.events.collect { yield(); refreshConnection() } }
        }
    }
    override fun onPause() { observation?.cancel(); observation=null; super.onPause() }
    override fun onDestroy() {
        pendingWirelessAddress=null; wirelessPermissionPending=false
        observation?.cancel(); uiScope.cancel()
        wirelessSettings?.close(); wirelessSettings=null
        installedIdentitySettings?.close(); installedIdentitySettings=null
        super.onDestroy()
    }
    @Deprecated("Platform back behavior is retained for this small Activity")
    override fun onBackPressed() { if (screen != Screen.HOME) showScreen(Screen.HOME) else super.onBackPressed() }

    private fun showScreen(next: Screen) {
        screen=next; port=null; connectionTitle=null; connectionDetail=null
        homeAction=null; startButton=null; stopButton=null; eventsView=null
        voiceStatus=null; voiceSetup=null; callsStatus=null; smsStatus=null; summaries.clear()
        ui.begin(next)
        when(next) {
            Screen.HOME -> showHome()
            Screen.CONNECTION -> showConnection()
            Screen.MAPS -> showMaps()
            Screen.MESSAGES -> showMessages()
            Screen.CALLS -> showCalls()
            Screen.VOICE -> showVoice()
            Screen.ADVANCED -> showAdvanced()
        }
        refreshSetup()
    }
    private fun showHome() {
        ui.paragraph("Your car dashboard, set up one feature at a time.")
        val connection=connectionCardViews()
        homeAction=ui.button(connection,"Set up connection",true) {
            when(connectionCard().action) {
                PhoneSetupState.Action.STOP_CONNECTION -> stopConnection()
                PhoneSetupState.Action.START_CONNECTION -> startProjection()
                PhoneSetupState.Action.SETUP_CONNECTION -> showScreen(Screen.CONNECTION)
            }
        }
        ui.button(connection,"Open dashboard preview") { startActivity(Intent(this,PreviewActivity::class.java)) }
        for(page in listOf(Screen.CONNECTION,Screen.MAPS,Screen.MESSAGES,Screen.CALLS,Screen.VOICE,Screen.ADVANCED))
            summaries[page]=ui.menu(page.title,"") { showScreen(page) }
        ui.menu("Music library","Choose local audio files for the car player") {
            startActivity(Intent(this,AudioLibraryActivity::class.java))
        }
    }
    private fun connectionCardViews(): LinearLayout {
        val card=ui.card("")
        connectionTitle=ui.label("Not connected",22f).also(card::addView)
        connectionDetail=ui.label("",muted=true).also {
            card.addView(it,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=ui.dp(8) })
        }
        return card
    }
    private fun showConnection() {
        ui.paragraph("Use USB, choose a paired car for wireless, or connect the desktop simulator. Car and wireless compatibility are experimental.")
        val controls=connectionCardViews()
        startButton=ui.button(controls,"Start connection",true,::startProjection).apply { contentDescription="openauto.start.desktop" }
        stopButton=ui.button(controls,"Stop connection",action=::stopConnection).apply { contentDescription="openauto.stop.desktop" }
        val access=ui.card("Connection access","Location supports your map. Nearby access is used for the selected car; Android may ask to allow the connection notification.")
        ui.button(access,"Review core permissions",action=::requestNeededPermissions)
        ui.button(access,"Allow nearby device access") { requestWirelessPermissions(null) }
        val identity=installedIdentitySettings ?: InstalledAaIdentitySettings(this).also { installedIdentitySettings=it }
        ui.attach(ui.card(""),identity.view)
        val wireless=wirelessSettings ?: WirelessCarSettings(this,::startWirelessProjection,::stopConnection).also { wirelessSettings=it }
        ui.attach(ui.card(""),wireless.view)
        val help=ui.card("USB and desktop","Start prefers the connected USB accessory. With no USB accessory, it waits for the desktop connection. The desktop port is in Advanced.")
        ui.button(help,"Open dashboard preview") { startActivity(Intent(this,PreviewActivity::class.java)) }
    }
    private fun showMaps() {
        ui.paragraph("Find places in the car. Keep your own Saved and Recent destinations on this phone.")
        val saved=ui.card("Saved places","Add, rename or remove destinations, including Home and Work. These are local to Lite and do not import Google Maps lists.")
        ui.button(saved,"Add or edit Saved places",true) { org.openandroidauto.navigation.SavedPlacesActivity.open(this) }
        val offline=ui.card("Offline areas","Download maps, driving directions and searchable addresses together. Nearby areas share their larger road-data download.")
        ui.button(offline,"Manage offline maps",true) { org.openandroidauto.offline.OfflineMapsActivity.open(this) }
        val speed=ui.card("Speed units")
        val speedSummary=ui.label(org.openandroidauto.navigation.SpeedDisplayPreferences.summary(this),muted=true).also(speed::addView)
        ui.button(speed,"Choose speed units") {
            org.openandroidauto.navigation.SpeedDisplayPreferences.openChooser(this) {
                speedSummary.text=org.openandroidauto.navigation.SpeedDisplayPreferences.summary(this)
            }
        }
        val map=ui.card("Map access","Location access is needed for your position. Complete offline areas include maps, directions and local address search. Outside those areas, navigation needs connectivity.")
        ui.button(map,"App permission settings",action=::openAppSettings)
        ui.button(map,"Open dashboard preview") { startActivity(Intent(this,PreviewActivity::class.java)) }
        ui.button(map,"Map information and data licenses",action=::showMapInformation)
    }
    /** Normal phone Activity owns this dialog and its explicitly tapped web links. */
    private fun showMapInformation() {
        val credits=TextView(this).apply {
            setTextColor(CarUi.TEXT);setLinkTextColor(CarUi.ACCENT);textSize=16f
            setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(20))
            text=android.text.Html.fromHtml("""
                <h3>OpenStreetMap contributors</h3>
                <p>Map, route and open place-search data come from OpenStreetMap. The data is available under the Open Database License (ODbL) 1.0.</p>
                <p><a href="https://www.openstreetmap.org/copyright">OpenStreetMap data and license information</a></p>
                <h3>© OpenMapTiles</h3>
                <p>The map schema and cartographic design are licensed under Creative Commons Attribution 4.0 (CC BY 4.0).</p>
                <p><a href="https://openmaptiles.org/">OpenMapTiles</a><br><a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0 license</a></p>
                <h3>OpenFreeMap</h3>
                <p>Provides the default map tiles. <a href="https://openfreemap.org/">OpenFreeMap</a></p>
                <h3>MapLibre Native</h3>
                <p>Draws the map on the phone. <a href="https://maplibre.org/">MapLibre</a></p>
                <p>These links open in a browser on this phone. The car's map information sheet also shows notices declared by the active map style.</p>
            """.trimIndent(),android.text.Html.FROM_HTML_MODE_LEGACY)
            movementMethod=android.text.method.LinkMovementMethod.getInstance()
        }
        val scroll=android.widget.ScrollView(this).apply { setBackgroundColor(CarUi.BACKGROUND);addView(credits) }
        android.app.AlertDialog.Builder(this).setTitle("Map information and data licenses")
            .setView(scroll).setPositiveButton("Done",null).show()
    }
    private fun showMessages() {
        ui.paragraph("Choose the apps whose current notifications may appear in the car. Replies always need your reviewed Send.")
        val view=messageSettings ?: NotificationSettingsView(this).also { messageSettings=it }
        ui.attach(ui.card(""),view)
        val sms=ui.card("New carrier SMS · optional","This is separate from a messaging app's notification Reply action. Carrier charges may apply.")
        smsStatus=ui.label(smsSummary(),muted=true).also(sms::addView)
        ui.button(sms,"Open new SMS setup") { startActivity(Intent(this,org.openandroidauto.sms.SmsSettingsActivity::class.java)) }
    }
    private fun showCalls() {
        ui.paragraph("Search contacts or use Android's existing Favorites, choose one exact number, then confirm Call.")
        val calls=ui.card("Calling access","Contacts and Phone access are requested only from the explicit setup screen. No call-log or default-dialer access is required.")
        callsStatus=ui.label(callsSummary(),muted=true).also(calls::addView)
        ui.button(calls,"Open Calls setup",true) { startActivity(Intent(this,org.openandroidauto.calls.CallsSettingsActivity::class.java)) }
        ui.card("You stay in control","Spoken commands only search contact choices. Calls require an active car connection, a current exact-number confirmation and Android's normal phone service. Preview cannot place calls.")
    }
    private fun showVoice() {
        ui.paragraph("Voice input is optional. Maps, messages, Calls and Music remain available without the speech add-on.")
        val voice=ui.card("Offline English input")
        voiceStatus=ui.label("",muted=true).also(voice::addView)
        voiceSetup=ui.button(voice,"Open voice input setup",true) {
            val target=dictationSetupIntent()
            if(target==null)refreshSetup()
            else try { startActivity(target) }
            catch(_:SecurityException) { refreshSetup() }
            catch(_:android.content.ActivityNotFoundException) { refreshSetup() }
        }
        ui.card("Model and readiness","Install the separate matching Open Auto Dictation APK, then download or import the English model and choose Check native engine in its setup. An installed add-on alone does not prove the model is ready.")
        ui.card("Use it in the car","Voice accepts a small command such as Call a name or Directions to a place. The explicit Dictate buttons enter a raw name or a reviewed message body. The car microphone opens only after the recognizer is ready. A transcript never sends, dials or starts a route by itself.")
    }
    private fun showAdvanced() {
        ui.paragraph("Connection details and developer options. Everyday car controls are on Home and Connection.")
        val tcp=ui.card("Desktop connection port","Used only when Start has no USB accessory. Stop the current connection before changing it.")
        port=EditText(this).apply {
            inputType=InputType.TYPE_CLASS_NUMBER;filters=arrayOf(InputFilter.LengthFilter(5));setSingleLine(true)
            setTextColor(CarUi.TEXT);setHintTextColor(CarUi.TEXT_MUTED);setText(portValue)
            contentDescription="Desktop connection port"
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { portValue=s?.toString().orEmpty() }
                override fun afterTextChanged(s:Editable?) {}
            })
            isEnabled=!ServiceState.projectionActive
        }.also(tcp::addView)
        val log=ui.card("Recent connection details")
        eventsView=ui.label("No connection events yet",14f,true).also(log::addView)
        ui.button(log,"Copy last wireless report") {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                android.content.ClipData.newPlainText("LibreAuto connection stages",WirelessConnectionReport.read(this)))
            Toast.makeText(this,"Connection stages copied. No network credentials or conversation text are included.",Toast.LENGTH_SHORT).show()
        }
        ui.button(ui.card("Android access"),"Open app permission settings",action=::openAppSettings)
        ui.card("About this preview","An independent dashboard for Maps, messages, Calls and local Music. Physical-car, HiBreak and daily wireless acceptance remain separate. The official Android Auto scaling helper is unchanged.")
    }

    private fun connectionCard()=PhoneSetupState.connection(ServiceState.projectionActive,
        ServiceState.connectionState.value.name,InstalledAaIdentitySettings.isEnabled(this),
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    private fun refreshConnection() {
        if(!::ui.isInitialized || isDestroyed)return
        val active=ServiceState.projectionActive
        if(!active)stopRequested=false
        val card=connectionCard()
        connectionTitle?.text=if(stopRequested)"Stopping connection…" else card.title
        connectionDetail?.text=when {
            stopRequested -> "Waiting for the current session to close."
            ServiceState.connectionState.value == ServiceState.ConnectionState.ERROR ->
                listOfNotNull(ServiceState.events.value.lastOrNull()?.take(300),card.detail).joinToString("\n")
            else -> card.detail
        }
        homeAction?.apply {
            text=if(stopRequested)"Stopping…" else card.actionLabel;isEnabled=!stopRequested
            contentDescription=when(card.action) {
                PhoneSetupState.Action.START_CONNECTION -> "openauto.start.desktop"
                PhoneSetupState.Action.STOP_CONNECTION -> "openauto.stop.desktop"
                PhoneSetupState.Action.SETUP_CONNECTION -> "Open connection setup"
            }
        }
        startButton?.isEnabled=!active && !stopRequested
        stopButton?.isEnabled=active && !stopRequested
        port?.isEnabled=!active
        eventsView?.text=ServiceState.events.value.takeLast(8).joinToString("\n").ifBlank { "No connection events yet" }
        wirelessSettings?.setConnecting(active)
        installedIdentitySettings?.refresh()
        summaries[Screen.CONNECTION]?.text=card.title
    }
    private fun refreshSetup() {
        if(!::ui.isInitialized || isDestroyed)return
        refreshConnection()
        messageSettings?.refresh();wirelessSettings?.refresh()
        val voiceAvailable=dictationSetupIntent()!=null
        callsStatus?.text=callsSummary()
        smsStatus?.text=smsSummary()
        voiceSetup?.isEnabled=voiceAvailable
        voiceStatus?.text=if(voiceAvailable)"Matching voice add-on installed. Open its setup to check the English model."
            else "Matching voice add-on is not available. Install its separate APK signed with the same certificate as Lite."
        summaries[Screen.MAPS]?.text="Saved, Recent and local Home / Work shortcuts"
        val notificationAccess=NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        summaries[Screen.MESSAGES]?.text=when {
            !notificationAccess -> "Choose notification access and messaging apps"
            NotificationSources.selected(this).isEmpty() -> "Notification access on · choose messaging apps"
            else -> "${NotificationSources.selected(this).size} notification source(s) selected · ${smsSummary()}"
        }
        summaries[Screen.CALLS]?.text=callsSummary()
        summaries[Screen.VOICE]?.text=if(voiceAvailable)"Add-on installed · check model in setup" else "Optional · separate add-on needed"
        summaries[Screen.ADVANCED]?.text="Desktop port, connection report and Android access"
    }
    private fun callsSummary()=when {
        !CallsAccess.enabled(this) -> "Calls are off"
        !CallsAccess.canCall(this) -> "Contacts or Phone permission is needed"
        else -> "Calls access enabled · choose and confirm every number"
    }
    private fun smsSummary()=when {
        !SmsAccess.enabled(this) -> "New SMS is off"
        !SmsAccess.canSend(this) -> "New SMS needs Android access"
        SmsAccess.defaultSubscription()<0 -> "New SMS needs a default SMS SIM"
        else -> "New SMS enabled · review every Send"
    }
    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",packageName,null))) }

    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==CORE_PERMISSIONS_REQUEST)corePermissionPending=false
        wirelessSettings?.refresh()
        if(requestCode==WIRELESS_PERMISSIONS_REQUEST) {
            wirelessPermissionPending=false
            val address=pendingWirelessAddress.also { pendingWirelessAddress=null }
            if(missingWirelessPermissions().isEmpty()) {
                if(address!=null && !isFinishing && !isDestroyed)startWirelessProjection(address)
            } else wirelessSettings?.setStatus(if(Build.VERSION.SDK_INT<=32)
                "Android 12 needs Nearby devices and precise Location access for wireless Wi-Fi. You can allow these in the phone's app settings."
                else "Nearby access is needed to connect to the selected car. You can allow it in this phone's app settings.")
        }
        refreshSetup()
    }
    private fun requestNeededPermissions() {
        if(corePermissionPending)return
        val permissions=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.BLUETOOTH_CONNECT)
        if(Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing=permissions.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()) { corePermissionPending=true;requestPermissions(missing.toTypedArray(),CORE_PERMISSIONS_REQUEST) }
    }
    private fun startProjection() {
        if(ServiceState.projectionActive) { ServiceState.addEvent("Stop the current connection before starting another.");return }
        val number=portValue.toIntOrNull()
        if(number==null || number !in 1024..65535) { showScreen(Screen.ADVANCED);port?.error="Use a port between 1024 and 65535";return }
        stopRequested=false
        launchProjection(Intent(this,ProjectionService::class.java).putExtra(ProjectionService.EXTRA_PORT,number))
    }
    private fun stopConnection() {
        pendingWirelessAddress=null
        stopRequested=stopService(Intent(this,ProjectionService::class.java)) || ServiceState.projectionActive
        refreshConnection()
    }
    private fun missingWirelessPermissions():List<String> {
        val required=mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
        required+=if(Build.VERSION.SDK_INT>=33)Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return required.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
    }
    private fun requestWirelessPermissions(address:String?) {
        if(ServiceState.projectionActive) { wirelessSettings?.setStatus("Stop the current connection before changing connection access.");return }
        if(wirelessPermissionPending || corePermissionPending)return
        pendingWirelessAddress=address
        val missing=missingWirelessPermissions()
        if(missing.isEmpty()) {
            pendingWirelessAddress=null;wirelessSettings?.refresh()
            if(address!=null)startWirelessProjection(address)
            return
        }
        val request=if(Manifest.permission.ACCESS_FINE_LOCATION in missing)(missing+Manifest.permission.ACCESS_COARSE_LOCATION).distinct() else missing
        wirelessPermissionPending=true;requestPermissions(request.toTypedArray(),WIRELESS_PERMISSIONS_REQUEST)
    }
    private fun startWirelessProjection(address:String) {
        if (!ProjectionAvailability.isSupported()) { allowProjectionEntry(); return }
        if(ServiceState.projectionActive) { wirelessSettings?.setStatus("Stop the current connection before connecting to a car.");return }
        if(missingWirelessPermissions().isNotEmpty()) { requestWirelessPermissions(address);return }
        val wifiEnabled=try { applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled==true } catch(_:SecurityException) { false }
        if(!wifiEnabled) { wirelessSettings?.setStatus("Turn on Wi-Fi in the phone's settings, then connect again.");return }
        if(Build.VERSION.SDK_INT<=32 && getSystemService(LocationManager::class.java)?.isLocationEnabled!=true) {
            wirelessSettings?.setStatus("Android 12 needs phone Location enabled for this Wi-Fi connection. Turn it on in phone settings, then try again.");return
        }
        stopRequested=false;wirelessSettings?.setConnecting(true)
        launchProjection(Intent(this,ProjectionService::class.java).putExtra(ProjectionService.EXTRA_TRANSPORT,"wireless-car")
            .putExtra(ProjectionService.EXTRA_BLUETOOTH_ADDRESS,address))
    }
    private fun launchProjection(intent:Intent) {
        if (!allowProjectionEntry()) return
        ProjectionIntegration.prepareToStart(this) { ready ->
            if (isFinishing || isDestroyed) return@prepareToStart
            if (!ready || !ProjectionIntegration.isProjectionAllowed()) {
                wirelessSettings?.setConnecting(false)
                ServiceState.addEvent("The music player is still stopping or car-display mode changed. Try connecting again.")
                refreshConnection()
                return@prepareToStart
            }
            try { startForegroundService(intent) }
            catch(error:Exception) {
                if(error !is SecurityException && !(Build.VERSION.SDK_INT>=31 && error is android.app.ForegroundServiceStartNotAllowedException))throw error
                wirelessSettings?.setConnecting(false)
                if(!ServiceState.projectionActive)ServiceState.connectionState.value=ServiceState.ConnectionState.ERROR
                ServiceState.addEvent("Projection could not start. Reopen LibreAuto, review app permissions and try again")
            }
            refreshConnection()
        }
    }
    private fun allowProjectionEntry(): Boolean {
        val reason = ProjectionAvailability.unavailableReason() ?: return true
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        finish()
        return false
    }
    /** One known optional component. Checking it never binds or starts the recognizer. */
    @Suppress("DEPRECATION")
    private fun dictationSetupIntent():Intent? {
        val component=ComponentName("org.openandroidauto.dictation","org.openandroidauto.dictation.ModelSetupActivity")
        return try {
            val info=packageManager.getActivityInfo(component,0)
            if(!info.enabled || !info.applicationInfo.enabled || !info.exported ||
                packageManager.checkSignatures(Process.myUid(),info.applicationInfo.uid)!=PackageManager.SIGNATURE_MATCH) null
            else Intent(Intent.ACTION_MAIN).setComponent(component)
        } catch(_:PackageManager.NameNotFoundException) { null }
        catch(_:SecurityException) { null }
    }
}
