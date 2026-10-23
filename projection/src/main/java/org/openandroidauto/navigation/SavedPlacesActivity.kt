// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.navigation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.openandroidauto.ui.CarUi
import org.openandroidauto.ui.PhoneActivityLauncher
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-only address/nickname editor. A resolver answer is always a candidate, never a save or route. */
class SavedPlacesActivity : Activity() {
    companion object {
        private const val NAME="destination-name"
        private const val LAT="destination-latitude"
        private const val LON="destination-longitude"
        @JvmStatic fun open(context:Context) = PhoneActivityLauncher.open(context,Intent(context,SavedPlacesActivity::class.java))
        @JvmStatic fun saveCandidate(context:Context,name:String,latitude:Double,longitude:Double) = PhoneActivityLauncher.open(
            context,Intent(context,SavedPlacesActivity::class.java).putExtra(NAME,name).putExtra(LAT,latitude).putExtra(LON,longitude))
    }
    private enum class Page { LIST, EDIT, RESULTS, PIN }
    private val main=Handler(Looper.getMainLooper())
    private lateinit var store:DestinationStore
    private lateinit var column:LinearLayout
    private var status:TextView?=null
    private var page=Page.LIST
    private var editingId:String?=null
    private var nickname=""
    private var address=""
    private var shortcut=DestinationStore.Shortcut.NONE
    private val pin=SavedPlacePinGate()
    private var pinLabel:TextView?=null
    private var nameField:EditText?=null
    private var addressField:EditText?=null
    private var queryGeneration=0L
    private val lookupRunning=AtomicBoolean()
    private var lookup:SavedPlaceResolver?=null
    private var lookupThread:Thread?=null
    private var mapView:MapView?=null
    private var activityStarted=false
    private var activityResumed=false
    private var mapStarted=false
    private var mapResumed=false
    private var backRegistration:java.io.Closeable?=null

    override fun onCreate(state:Bundle?) {
        super.onCreate(state);store=DestinationPreferences.open(this)
        if(state!=null&&state.getBoolean("editing")) {
            editingId=state.getString("saved-id");nickname=state.getString("nickname").orEmpty().take(200)
            address=state.getString("address").orEmpty().take(DestinationStore.MAX_ADDRESS_CHARS)
            shortcut=DestinationStore.Shortcut.values().getOrElse(state.getInt("shortcut")) { DestinationStore.Shortcut.NONE }
            pin.setAddress(address)
            if(state.getBoolean("pin-confirmed"))try { pin.existing(address,GeoPoint(state.getDouble(LAT),state.getDouble(LON))) } catch(_:IllegalArgumentException) {}
            showEditor()
        } else {
            val name=intent?.getStringExtra(NAME)
            val point=try { if(intent?.hasExtra(LAT)==true&&intent?.hasExtra(LON)==true)
                GeoPoint(intent.getDoubleExtra(LAT,Double.NaN),intent.getDoubleExtra(LON,Double.NaN)) else null } catch(_:IllegalArgumentException) { null }
            if(name!=null&&point!=null) {
                val existing=store.snapshot().saved.firstOrNull { samePoint(it.latitude,it.longitude,point.latitude,point.longitude) }
                if(existing!=null)beginPlace(existing.id,existing.name,existing.address,GeoPoint(existing.latitude,existing.longitude),existing.shortcut)
                else beginPlace(null,name.take(200),"",point,DestinationStore.Shortcut.NONE)
            } else showList()
        }
        if(Build.VERSION.SDK_INT>=33)backRegistration=Api33Back.register(this) { navigateBack() }
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putBoolean("editing",page!=Page.LIST);out.putString("saved-id",editingId)
        out.putString("nickname",nickname);out.putString("address",address);out.putInt("shortcut",shortcut.ordinal)
        pin.confirmedPoint()?.let { out.putBoolean("pin-confirmed",true);out.putDouble(LAT,it.latitude);out.putDouble(LON,it.longitude) }
        super.onSaveInstanceState(out)
    }
    override fun onStart() { super.onStart();activityStarted=true;mapView?.let { it.onStart();mapStarted=true } }
    override fun onResume() { super.onResume();activityResumed=true;mapView?.let { it.onResume();mapResumed=true } }
    override fun onPause() { if(mapResumed)mapView?.onPause();mapResumed=false;activityResumed=false;super.onPause() }
    override fun onStop() { cancelLookup();if(mapStarted)mapView?.onStop();mapStarted=false;activityStarted=false;super.onStop() }
    override fun onDestroy() { backRegistration?.close();backRegistration=null;cancelLookup();closeMap();main.removeCallbacksAndMessages(null);super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory();mapView?.onLowMemory() }
    @Deprecated("Keeps editor Back separate from the projected dashboard")
    override fun onBackPressed() = navigateBack()
    private fun navigateBack() { when(page) { Page.LIST->finish();Page.EDIT->showList();else->showEditor() } }
    @android.annotation.TargetApi(33)
    private object Api33Back {
        fun register(activity:Activity,action:()->Unit):java.io.Closeable {
            val callback=android.window.OnBackInvokedCallback { action() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback)
            return java.io.Closeable { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    }
    private fun screen(title:String,next:Page) {
        cancelLookup();closeMap();page=next;pinLabel=null;nameField=null;addressField=null
        column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(28));setBackgroundColor(CarUi.BACKGROUND) }
        setContentView(ScrollView(this).apply { addView(column) })
        text(title,26f);status=text("",15f,true)
    }
    private fun showList() {
        screen("Saved places",Page.LIST)
        text("Add an address and nickname, or choose a pin. Saved places stay in this app; Home and Work are optional shortcuts.",16f,true)
        button(column,"Add an address",true,"openauto.saved.add") { beginPlace(null,"","",null,DestinationStore.Shortcut.NONE) }
        val snapshot=store.snapshot()
        if(snapshot.saved.isEmpty())text("No saved places yet. Add your first address above.",17f,true)
        for(place in snapshot.saved) {
            val card=card();val prefix=when(place.shortcut) { DestinationStore.Shortcut.HOME->"Home · ";DestinationStore.Shortcut.WORK->"Work · ";else->"" }
            text(prefix+place.name,20f,parent=card)
            text(place.address.ifEmpty { coordinates(GeoPoint(place.latitude,place.longitude)) },15f,true,card)
            button(card,"Edit nickname, address or pin") { beginPlace(place.id,place.name,place.address,GeoPoint(place.latitude,place.longitude),place.shortcut) }
            button(card,"Remove") {
                AlertDialog.Builder(this).setTitle("Remove saved place?").setMessage(place.name).setNegativeButton("Cancel",null)
                    .setPositiveButton("Remove") { _,_->store.removeSaved(place.id);showList() }.show()
            }
        }
        text("Recent destinations",22f)
        if(snapshot.recent.isEmpty())text("Places appear here after you select a destination on Map.",15f,true)
        for(place in snapshot.recent) {
            val card=card();text(place.name,18f,parent=card)
            if(place.address.isNotEmpty())text(place.address,15f,true,card)
            button(card,"Save this destination") {
                val existing=store.snapshot().saved.firstOrNull { samePoint(it.latitude,it.longitude,place.latitude,place.longitude) }
                if(existing!=null)beginPlace(existing.id,existing.name,existing.address,GeoPoint(existing.latitude,existing.longitude),existing.shortcut)
                else beginPlace(null,place.name,place.address,GeoPoint(place.latitude,place.longitude),DestinationStore.Shortcut.NONE)
            }
        }
        button(column,"Clear Recent") {
            AlertDialog.Builder(this).setTitle("Clear Recent?").setMessage("Saved places, Home and Work will stay.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear") { _,_->store.clearRecent();showList() }.show()
        }.isEnabled=snapshot.recent.isNotEmpty()
        button(column,"Done") { finish() }
    }
    private fun beginPlace(id:String?,name:String,address:String,point:GeoPoint?,slot:DestinationStore.Shortcut) {
        editingId=id;nickname=name;this.address=address;shortcut=slot;pin.setAddress(address);pin.invalidate()
        if(point!=null)pin.existing(address,point)
        showEditor()
    }
    private fun showEditor() {
        screen(if(editingId==null)"Add a saved place" else "Edit saved place",Page.EDIT)
        text("Nickname",17f)
        nameField=field("e.g. Church",nickname,200) { nickname=it }.apply { contentDescription="openauto.saved.nickname" }
        text("Address · include suite or unit",17f)
        addressField=field("Street, city, state and postal code",address,DestinationStore.MAX_ADDRESS_CHARS) {
            address=it;pin.setAddress(it);refreshPinLabel()
        }.apply { contentDescription="openauto.saved.address" }
        text("Find uses the phone's address service, with the configured open map search as a fallback. A building-only lookup may omit the unit; your full address is kept. Review every returned pin.",14f,true)
        button(column,"Find address",true,"openauto.saved.find") { findAddress() }
        val pinCard=card();text("Destination pin",19f,parent=pinCard)
        pinLabel=text("",15f,true,pinCard);refreshPinLabel()
        button(pinCard,"Choose a pin on the map") { showPin(pin.confirmedPoint(),"Tap the exact destination or entrance on the map.") }
        button(pinCard,"Enter / paste coordinates") { coordinateDialog() }
        text("Shortcut",17f)
        val slots=RadioGroup(this)
        for(slot in DestinationStore.Shortcut.values())slots.addView(RadioButton(this).apply {
            id=100+slot.ordinal;text=when(slot) { DestinationStore.Shortcut.NONE->"Saved place";DestinationStore.Shortcut.HOME->"Home";DestinationStore.Shortcut.WORK->"Work" };setTextColor(CarUi.TEXT)
        })
        slots.check(100+shortcut.ordinal);slots.setOnCheckedChangeListener { _,id->shortcut=DestinationStore.Shortcut.values()[id-100] };column.addView(slots)
        button(column,"Save place",true,"openauto.saved.save") { savePlace() }
        button(column,"Cancel") { showList() }
    }
    private fun refreshPinLabel() { pinLabel?.text=pin.confirmedPoint()?.let { "Confirmed pin · "+coordinates(it) }
        ?: "No confirmed pin. Find the address, choose a map pin, or paste coordinates before saving." }
    private fun savePlace() {
        val point=pin.confirmedPoint()
        if(point==null) { status?.text="Confirm the destination pin before saving. An address alone is not a verified location.";return }
        try {
            val id=editingId
            if(id==null) {
                val existing=store.snapshot().saved.firstOrNull { samePoint(it.latitude,it.longitude,point.latitude,point.longitude) }
                if(existing!=null) {
                    AlertDialog.Builder(this).setTitle("This pin is already saved").setMessage("Open "+existing.name+" to edit its nickname, address or shortcut.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Edit existing") { _,_->
                            beginPlace(existing.id,existing.name,existing.address,GeoPoint(existing.latitude,existing.longitude),existing.shortcut)
                        }.show()
                    return
                }
            }
            if(id==null)store.save(nickname,address,point.latitude,point.longitude,shortcut)
            else if(!store.update(id,nickname,address,point.latitude,point.longitude,shortcut)) {
                status?.text="This saved place was removed. Cancel and add it again.";return
            }
            showList();status?.text="Saved. Choose it from Saved on the car map."
        } catch(error:IllegalArgumentException) { status?.text=error.message;nameField?.error=if(nickname.isBlank())"Enter a nickname" else null }
        catch(_:IllegalStateException) { status?.text="Saved places are full or unavailable. Remove a place and try again." }
    }
    private fun findAddress() {
        val queries=try { SavedAddressQueries.forAddress(address) } catch(error:IllegalArgumentException) { addressField?.error=error.message;return }
        if(!lookupRunning.compareAndSet(false,true)) { status?.text="The prior address check is finishing. Try Find again shortly.";return }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(addressField?.windowToken,0)
        pin.invalidate()
        val resolver=try { SavedPlaceResolver(this,NavigationConfig.load(this)) } catch(_:IllegalArgumentException) { lookupRunning.set(false);status?.text="Check navigation provider settings.";return }
        val query=queries.first();screen("Choose the correct location",Page.RESULTS)
        // screen cancels old work only; publish the new request after that transition.
        lookup=resolver;val generation=++queryGeneration
        text(address,18f);text("Checking address services…",16f,true)
        button(column,"Cancel search") { showEditor() }
        val worker=Thread({
            var result:List<NavigationProviders.Place> = emptyList()
            var failed=false
            try { result=resolver.find(query) } catch(_:InterruptedException) { failed=true } catch(_:Exception) { failed=true }
            finally { lookupRunning.set(false) }
            main.post {
                if(isFinishing||isDestroyed||queryGeneration!=generation||page!=Page.RESULTS)return@post
                lookup=null;lookupThread=null;showCandidates(result,failed)
            }
        },"OpenAutoSavedAddress")
        lookupThread=worker
        try { worker.start() }
        catch(_:OutOfMemoryError) { lookupRunning.set(false);lookup=null;lookupThread=null;showEditor();status?.text="The address check could not start. Try again." }
        catch(_:RuntimeException) { lookupRunning.set(false);lookup=null;lookupThread=null;showEditor();status?.text="The address check could not start. Try again." }
    }
    private fun showCandidates(found:List<NavigationProviders.Place>,failed:Boolean) {
        screen("Choose the correct location",Page.RESULTS);text(address,18f)
        text("Check the location and entrance. Search results can be approximate; nothing is saved until you confirm a pin and tap Save.",15f,true)
        if(found.isEmpty())text(if(failed)"The address check did not finish. Try again, or choose a pin / paste coordinates."
            else "No location was returned, or the address service is unavailable. Try a simpler address, or choose a pin / paste coordinates.",17f)
        for(place in found.take(5)) {
            val card=card();text(place.name,18f,parent=card);text(coordinates(place.point),14f,true,card)
            button(card,"Review this pin",true) { showPin(place.point,place.name) }
        }
        button(column,"Choose a pin on the map") { showPin(pin.confirmedPoint(),"Tap the exact destination or entrance on the map.") }
        button(column,"Enter / paste coordinates") { coordinateDialog() }
        button(column,"Edit address") { showEditor() }
    }
    private fun coordinateDialog() {
        val value=EditText(this).apply { setSingleLine(true);hint="latitude, longitude";filters=arrayOf(InputFilter.LengthFilter(80))
            setText(pin.confirmedPoint()?.let { "${it.latitude}, ${it.longitude}" }.orEmpty()) }
        val dialog=AlertDialog.Builder(this).setTitle("Paste destination coordinates").setMessage("Use latitude, longitude from a location you have checked. You will review the pin before saving.")
            .setView(value).setNegativeButton("Cancel",null).setPositiveButton("Review pin",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val fields=value.text.toString().split(',')
            val point=try { if(fields.size!=2)null else GeoPoint(fields[0].trim().toDouble(),fields[1].trim().toDouble()) } catch(_:IllegalArgumentException) { null }
            if(point==null) { value.error="Enter finite latitude -90…90, longitude -180…180";return@setOnClickListener }
            dialog.dismiss();showPin(point,"Manually entered coordinates")
        } };dialog.show()
    }
    @Suppress("DEPRECATION")
    private fun showPin(initial:GeoPoint?,description:String) {
        screen("Review the destination pin",Page.PIN);text(nickname.ifBlank { "Destination" },22f);text(address,16f,true);text(description,16f)
        text("Tap to adjust the pin. Confirm the actual entrance you want to navigate to; no route starts here.",15f,true)
        val selected=text(initial?.let(::coordinates)?:"No pin selected · tap a location",15f)
        val ticket= arrayOf<SavedPlacePinGate.Review?>(initial?.let(pin::review))
        MapLibre.getInstance(applicationContext)
        org.maplibre.android.module.http.HttpRequestUtil.setLogEnabled(false)
        val view=MapView(this,MapLibreMapOptions().compassEnabled(false).rotateGesturesEnabled(false).attributionEnabled(true))
        mapView=view;view.onCreate(null);view.setMaximumFps(15)
        column.addView(view,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(320)))
        val use=button(column,"Use this pin",true,"openauto.saved.confirm-pin") {
            if(!pin.confirm(ticket[0])) { status?.text="That pin is no longer current. Choose it again.";return@button }
            showEditor();status?.text="Pin confirmed. Check the nickname and address, then Save."
        }.apply { isEnabled=initial!=null }
        button(column,"Back to address") { showEditor() }
        if(activityStarted) { view.onStart();mapStarted=true };if(activityResumed) { view.onResume();mapResumed=true }
        view.getMapAsync { map ->
            if(mapView!==view||isDestroyed)return@getMapAsync
            var marker:Marker?=null
            fun select(point:GeoPoint) {
                ticket[0]=pin.review(point);selected.text=coordinates(point);use.isEnabled=true
                marker?.let(map::removeMarker);marker=map.addMarker(MarkerOptions().position(LatLng(point.latitude,point.longitude)))
            }
            initial?.let { point ->
                map.cameraPosition=CameraPosition.Builder().target(LatLng(point.latitude,point.longitude)).zoom(16.0).build()
                marker=map.addMarker(MarkerOptions().position(LatLng(point.latitude,point.longitude)))
            } ?: run { map.cameraPosition=CameraPosition.Builder().target(LatLng(0.0,0.0)).zoom(1.0).build() }
            map.addOnMapClickListener { point -> if(mapView===view)select(GeoPoint(point.latitude,point.longitude));true }
            try { map.setStyle(NavigationConfig.load(this).styleUrl) }
            catch(_:RuntimeException) { status?.text="Map tiles are unavailable. Independently verify the coordinate values before using this pin." }
        }
    }
    private fun cancelLookup() { queryGeneration++;lookup?.cancel();lookup=null;lookupThread?.interrupt() }
    private fun closeMap() {
        val view=mapView?:return;mapView=null
        if(mapResumed)view.onPause();if(mapStarted)view.onStop();mapResumed=false;mapStarted=false;view.onDestroy()
    }
    private fun card()=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;background=CarUi.rounded(this@SavedPlacesActivity,CarUi.SURFACE,16);setPadding(dp(14),dp(12),dp(14),dp(12))
        column.addView(this,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(12);bottomMargin=dp(8) })
    }
    private fun text(value:String,size:Float=16f,muted:Boolean=false,parent:LinearLayout=column)=CarUi.text(this,value,size,if(muted)CarUi.TEXT_MUTED else CarUi.TEXT).also {
        it.setPadding(0,dp(6),0,dp(6));parent.addView(it)
    }
    private fun button(parent:LinearLayout,label:String,primary:Boolean=false,description:String?=null,action:()->Unit)=CarUi.button(this,label,primary).also {
        it.setOnClickListener { action() };if(description!=null)it.contentDescription=description
        parent.addView(it,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin=dp(8) })
    }
    private fun field(hint:String,value:String,limit:Int,changed:(String)->Unit)=EditText(this).apply {
        setSingleLine(true);this.hint=hint;setTextColor(CarUi.TEXT);setHintTextColor(CarUi.TEXT_MUTED)
        inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters=arrayOf(InputFilter.LengthFilter(limit));setText(value)
        addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s:Editable?) {}
        });column.addView(this)
    }
    private fun dp(value:Int)=CarUi.dp(this,value.toFloat())
    private fun coordinates(point:GeoPoint)=String.format(Locale.US,"%.6f, %.6f",point.latitude,point.longitude)
    private fun samePoint(a:Double,b:Double,c:Double,d:Double)=Math.round(a*100000)==Math.round(c*100000)&&Math.round(b*100000)==Math.round(d*100000)
}
