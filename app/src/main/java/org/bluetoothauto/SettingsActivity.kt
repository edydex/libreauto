// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import org.bluetoothauto.navigation.OwnNavigation
import org.bluetoothauto.navigation.HintPlacement

class SettingsActivity:Activity(){
    private lateinit var page:LinearLayout
    private fun dp(n:Int)=PlayerUi.dp(this,n)
    override fun onCreate(state:Bundle?){super.onCreate(state);build()}
    override fun onResume(){super.onResume();build()}
    private fun build(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),dp(8));setBackgroundColor(PlayerUi.background)};setContentView(root)
        val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        bar.addView(PlayerUi.icon(this,"back","Back to player"){finish()},LinearLayout.LayoutParams(dp(48),dp(56)))
        bar.addView(PlayerUi.label(this,"Settings",23,true));root.addView(bar)
        val scroll=ScrollView(this);page=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(16),dp(4),dp(20))};scroll.addView(page);root.addView(scroll)
        section("Car display")
        row("OpenAuto & Android Auto",org.bluetoothauto.integration.CarDisplayController.mode(this).label){startActivity(Intent(this,CarDisplaySettingsActivity::class.java))}
        hint("Choose a car-screen mode, or keep using the Bluetooth music player.")
        section("Maps app")
        val maps=RadioGroup(this)
        listOf("GOOGLE" to "Google Maps","OPEN" to "Open maps").forEach{(value,name)->
            maps.addView(RadioButton(this).apply{text=name;setTextColor(PlayerUi.ink);textSize=17f;buttonTintList=android.content.res.ColorStateList.valueOf(PlayerUi.accent);minHeight=dp(52);isChecked=Library.navigator(this@SettingsActivity)==value
                setOnClickListener{
                    if(Library.navigator(this@SettingsActivity)!=value){
                        OwnNavigation.stop();GuidanceHub.clear();Library.navigator(this@SettingsActivity,value)
                        if(AppState.running)BluetoothAutoService.command(this@SettingsActivity,BluetoothAutoService.LIVE)
                        if(value=="GOOGLE")MapsNotificationListener.refresh(this@SettingsActivity)
                    }
                }
            })
        };page.addView(maps)
        hint("The map icon opens your selected app. Open maps includes search, saved places and routing.")
        section("Next turn hint in")
        val placement=RadioGroup(this)
        listOf(HintPlacement.TITLE_ONLY to "Song title only",HintPlacement.ARTWORK_ONLY to "Album art only",HintPlacement.BOTH to "Both").forEach{(value,name)->
            placement.addView(RadioButton(this).apply{text=name;setTextColor(PlayerUi.ink);textSize=17f;buttonTintList=android.content.res.ColorStateList.valueOf(PlayerUi.accent);minHeight=dp(48);isChecked=Library.hintPlacement(this@SettingsActivity)==value
                setOnClickListener{Library.hintPlacement(this@SettingsActivity,value);if(AppState.running)BluetoothAutoService.command(this@SettingsActivity,BluetoothAutoService.HINTS)}
            })
        };page.addView(placement)
        hint("Title only keeps the music’s cover. Album art only keeps the original song title. Navigation and spoken cues continue in all three modes.")
        section("Audio notifications")
        val audio=RadioGroup(this)
        listOf("OFF" to "Off","DIRECTIONS" to "Directions","MESSAGES" to "Messages","BOTH" to "Directions & messages").forEach{(value,name)->
            audio.addView(RadioButton(this).apply{text=name;setTextColor(PlayerUi.ink);textSize=17f;buttonTintList=android.content.res.ColorStateList.valueOf(PlayerUi.accent);minHeight=dp(48);isChecked=Library.audioMode(this@SettingsActivity)==value
                setOnClickListener{Library.audioMode(this@SettingsActivity,value);if(AppState.running)BluetoothAutoService.command(this@SettingsActivity,BluetoothAutoService.SETTINGS)}
            })
        };page.addView(audio)
        hint("Music pauses while a cue is spoken, then resumes. Messages supports SMS, Telegram and Signal notification text. Google Maps’ own voice is controlled in Google Maps.")
        section("More")
        row("Distance units",when(Library.units(this)){"IMPERIAL"->"Miles / feet";"METRIC"->"Kilometers / meters";else->"Automatic"}){chooseUnits()}
        row("Connection & permissions",if(notificationAccess())"Maps guidance connected" else "Set up Maps guidance"){setup()}
        row("Artwork",if(Library.mapEnabled(this))"Map and turn arrow" else "Turn arrow only"){
            AlertDialog.Builder(this).setTitle("Stereo artwork").setMultiChoiceItems(arrayOf("Surrounding map"),booleanArrayOf(Library.mapEnabled(this))){_,_,checked->Library.mapEnabled(this,checked);if(AppState.running)BluetoothAutoService.command(this,BluetoothAutoService.SETTINGS)}
                .setPositiveButton("Done"){_,_->build()}.show()
        }
        row("Prepared maps","Saved road areas and upcoming turn pictures"){startActivity(Intent(this,PreparedMapsActivity::class.java))}
        row("Preview & diagnostics","Test stereo artwork while parked"){preview()}
        row("Stop session","End playback and location updates"){BluetoothAutoService.command(this,BluetoothAutoService.STOP);Toast.makeText(this,"Session stopped",Toast.LENGTH_SHORT).show()}
        row("About & remove","LibreAuto ${BuildConfig.VERSION_NAME}"){about()}
    }
    private fun chooseUnits(){
        val values=listOf("AUTO","IMPERIAL","METRIC")
        AlertDialog.Builder(this).setTitle("Distance units").setSingleChoiceItems(arrayOf("Automatic","Miles / feet","Kilometers / meters"),values.indexOf(Library.units(this))){d,i->
            Library.units(this,values[i]);if(AppState.running)BluetoothAutoService.command(this,BluetoothAutoService.SETTINGS);d.dismiss();build()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun setup(){
        AlertDialog.Builder(this).setTitle("Connection & permissions")
            .setItems(arrayOf("Pair a Bluetooth stereo","Allow Google Maps guidance","Allow location & playback notifications")){_,i->when(i){
                0->startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                1->try{startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(this,MapsNotificationListener::class.java).flattenToString()))}catch(_:Exception){startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}
                2->{val p=mutableListOf<String>();if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){p.add(Manifest.permission.ACCESS_FINE_LOCATION);p.add(Manifest.permission.ACCESS_COARSE_LOCATION)};if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(p.isNotEmpty())requestPermissions(p.toTypedArray(),52)else Toast.makeText(this,"Permissions already allowed",Toast.LENGTH_SHORT).show()}
            }}.setNegativeButton("Close",null).show()
    }
    override fun onRequestPermissionsResult(code:Int,p:Array<out String>,r:IntArray){super.onRequestPermissionsResult(code,p,r);if(AppState.running)BluetoothAutoService.command(this,BluetoothAutoService.SETTINGS)}
    private fun preview(){
        val view=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(12))}
        val preview=ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Stereo artwork preview";setImageBitmap(AppState.artwork)}
        view.addView(preview,LinearLayout.LayoutParams(-1,dp(240)))
        view.addView(PlayerUi.label(this,"${if(AppState.listenerConnected)"Maps access connected" else "Maps access not connected"} · ${AppState.artworkStatus}",13,false,PlayerUi.muted))
        val d=AlertDialog.Builder(this).setTitle("Stereo preview").setView(view)
            .setPositiveButton("Play demo"){_,_->BluetoothAutoService.command(this,BluetoothAutoService.DEMO);finish()}
            .setNeutralButton("Next demo turn"){_,_->BluetoothAutoService.command(this,BluetoothAutoService.DEMO_NEXT)}
            .setNegativeButton("Close",null).create();d.show()
    }
    private fun about(){
        AlertDialog.Builder(this).setTitle("LibreAuto ${BuildConfig.VERSION_NAME}")
            .setMessage("Music powered by VLC. Maps rendered with MapLibre / OpenStreetMap. Stereo artwork support depends on your car.\n\nTo remove: stop the session, turn off LibreAuto in Android’s notification-access settings, then uninstall. Your original music and Google Maps stay on your phone.")
            .setPositiveButton("Close",null).setNeutralButton("Open-source licenses"){_,_->
                val text=try{assets.open("NOTICE.txt").bufferedReader().use{it.readText()}}catch(_:Exception){"See the source package for licenses."}
                AlertDialog.Builder(this).setTitle("Licenses").setMessage(text).setPositiveButton("Close",null).show()
            }.show()
    }
    private fun notificationAccess()=Settings.Secure.getString(contentResolver,"enabled_notification_listeners")?.split(':')?.any{ComponentName.unflattenFromString(it)==ComponentName(this,MapsNotificationListener::class.java)}==true
    private fun section(text:String){page.addView(PlayerUi.label(this,text,15,true,PlayerUi.accent).apply{setPadding(0,dp(18),0,dp(5))})}
    private fun hint(text:String){page.addView(PlayerUi.label(this,text,13,false,PlayerUi.muted).apply{setPadding(0,dp(3),0,dp(12))})}
    private fun row(title:String,value:String,click:()->Unit){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;background=PlayerUi.shape(this@SettingsActivity,PlayerUi.card,14);setPadding(dp(15),dp(10),dp(6),dp(10));setOnClickListener{click()}}
        val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(PlayerUi.label(this@SettingsActivity,title,16,true));addView(PlayerUi.label(this@SettingsActivity,value,13,false,PlayerUi.muted).apply{setPadding(0,dp(4),0,0)})}
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f));row.addView(ImageView(this).apply{setImageDrawable(PlayerIcon("chevron",PlayerUi.muted));setPadding(dp(12),dp(12),dp(12),dp(12))},LinearLayout.LayoutParams(dp(44),dp(44)))
        page.addView(row,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(5),0,dp(3))})
    }
}
