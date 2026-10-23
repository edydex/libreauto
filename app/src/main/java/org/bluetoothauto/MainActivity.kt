// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import org.bluetoothauto.navigation.OwnMapsActivity

/** The phone is a music player; navigation and configuration have one entry point each. */
class MainActivity:Activity() {
    private lateinit var cover:ImageView
    private lateinit var sleeve:RecordSleeve
    private lateinit var title:TextView
    private lateinit var artist:TextView
    private lateinit var elapsed:TextView
    private lateinit var duration:TextView
    private lateinit var seek:SeekBar
    private lateinit var play:ImageButton
    private lateinit var queue:TextView
    private lateinit var addMusic:Button
    private lateinit var shuffle:Button
    private lateinit var error:TextView
    private var dragging=false
    private var pendingMaps=false
    private var renderedBitmap:android.graphics.Bitmap?=null
    private val observer:()->Unit={render()}
    private fun dp(n:Int)=PlayerUi.dp(this,n)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        pendingMaps=savedInstanceState?.getBoolean("pendingMaps") ?: false
        buildPlayer()
    }
    override fun onStart(){super.onStart();AppState.observers.add(observer);render()}
    override fun onResume(){super.onResume();render()}
    override fun onStop(){AppState.observers.remove(observer);super.onStop()}
    override fun onSaveInstanceState(out:Bundle){out.putBoolean("pendingMaps",pendingMaps);super.onSaveInstanceState(out)}

    private fun buildPlayer() {
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(PlayerUi.background);setPadding(dp(20),dp(2),dp(20),dp(8))}
        setContentView(root)
        val toolbar=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        toolbar.addView(PlayerUi.label(this,"LibreAuto",20,true),LinearLayout.LayoutParams(0,dp(58),1f).apply {gravity=Gravity.CENTER_VERTICAL})
        (toolbar.getChildAt(0) as TextView).gravity=Gravity.CENTER_VERTICAL
        toolbar.addView(PlayerUi.icon(this,"map","Open navigation"){pendingMaps=true;if(!requestNormalPermissions())openNavigator()},LinearLayout.LayoutParams(dp(48),dp(48)))
        toolbar.addView(PlayerUi.icon(this,"settings","Settings"){startActivity(Intent(this,SettingsActivity::class.java))},LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(toolbar)
        val scroll=ScrollView(this).apply {isFillViewport=true;isVerticalScrollBarEnabled=false}
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val landscape=resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE && resources.configuration.screenWidthDp>=600
        val body=LinearLayout(this).apply {orientation=if(landscape)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(10),0,dp(6))}
        scroll.addView(body)
        val coverSize=if(landscape)(resources.configuration.screenHeightDp-115).coerceIn(170,320) else (resources.configuration.screenHeightDp-405).coerceIn(180,350)
        val frame=FrameLayout(this).apply {
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,dp(22).toFloat())}}
            clipToOutline=true
        }
        sleeve=RecordSleeve(this);frame.addView(sleeve,FrameLayout.LayoutParams(-1,-1))
        cover=ImageView(this).apply {scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Album artwork";visibility=View.GONE;setBackgroundColor(PlayerUi.card)}
        frame.addView(cover,FrameLayout.LayoutParams(-1,-1))
        body.addView(frame,LinearLayout.LayoutParams(dp(coverSize),dp(coverSize)).apply {gravity=Gravity.CENTER_HORIZONTAL;if(landscape)setMargins(0,0,dp(28),0)else setMargins(0,0,0,dp(22))})
        val controls=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        body.addView(controls,if(landscape)LinearLayout.LayoutParams(0,-2,1f)else LinearLayout.LayoutParams(-1,-2))
        title=PlayerUi.label(this,"Your music, with directions.",25,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        artist=PlayerUi.label(this,"Choose a playlist or folder to get started",15,false,PlayerUi.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(5),0,dp(8))}
        controls.addView(title);controls.addView(artist)
        seek=SeekBar(this).apply {
            max=1000;progressTintList=android.content.res.ColorStateList.valueOf(PlayerUi.accent)
            thumbTintList=android.content.res.ColorStateList.valueOf(PlayerUi.accent)
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(PlayerUi.card)
            contentDescription="Playback position";setPadding(dp(3),0,dp(3),0)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s:SeekBar){dragging=true}
                override fun onStopTrackingTouch(s:SeekBar){
                    BluetoothAutoService.seek(this@MainActivity,AppState.playback.durationMs*s.progress/1000);dragging=false
                }
                override fun onProgressChanged(s:SeekBar,p:Int,fromUser:Boolean){if(fromUser)elapsed.text=time(AppState.playback.durationMs*p/1000)}
            })
        }
        controls.addView(seek,LinearLayout.LayoutParams(-1,dp(34)))
        val times=LinearLayout(this)
        elapsed=PlayerUi.label(this,"0:00",12,false,PlayerUi.muted)
        duration=PlayerUi.label(this,"0:00",12,false,PlayerUi.muted).apply{gravity=Gravity.END}
        times.addView(elapsed,LinearLayout.LayoutParams(0,-2,1f));times.addView(duration,LinearLayout.LayoutParams(0,-2,1f));controls.addView(times)
        val transport=LinearLayout(this).apply{gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(14))}
        transport.addView(PlayerUi.icon(this,"previous","Previous track",60){command(BluetoothAutoService.PREVIOUS)},LinearLayout.LayoutParams(dp(60),dp(60)))
        play=PlayerUi.icon(this,"play","Play",80,true){
            if(AppState.playback.track==null && Library.tracks(this).isEmpty())openLibrary()
            else {requestNotificationPermission();command(BluetoothAutoService.TOGGLE)}
        }
        transport.addView(play,LinearLayout.LayoutParams(dp(80),dp(80)).apply{setMargins(dp(24),0,dp(24),0)})
        transport.addView(PlayerUi.icon(this,"next","Next track",60){command(BluetoothAutoService.NEXT)},LinearLayout.LayoutParams(dp(60),dp(60)))
        controls.addView(transport)
        addMusic=PlayerUi.button(this,"Music library",true){openLibrary()}
        controls.addView(addMusic,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(8)})
        val library=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=PlayerUi.shape(this@MainActivity,PlayerUi.card,16);setPadding(dp(6),dp(3),dp(5),dp(3))}
        library.addView(PlayerUi.icon(this,"queue","Open music queue"){showQueue()},LinearLayout.LayoutParams(dp(48),dp(48)))
        queue=PlayerUi.label(this,"Your music",15,true).apply{setOnClickListener{showQueue()};gravity=Gravity.CENTER_VERTICAL;minHeight=dp(48)}
        library.addView(queue,LinearLayout.LayoutParams(0,-2,1f))
        shuffle=PlayerUi.button(this,"In order"){toggleShuffle()}.apply {textSize=13f;minimumWidth=0;setPadding(dp(10),0,dp(10),0)}
        library.addView(shuffle,LinearLayout.LayoutParams(-2,dp(44)).apply {setMargins(dp(4),0,dp(2),0)})
        controls.addView(library)
        error=PlayerUi.label(this,"",14,false,0xFFFFC8B5.toInt()).apply{setPadding(0,dp(12),0,0);setOnClickListener{AppState.error=null;render()}}
        controls.addView(error)
        render()
    }
    private fun render() {
        if(!::title.isInitialized)return
        val s=AppState.playback;val presentation=AppState.presentation?.takeIf{it.uri==s.track?.uri}
        title.text=presentation?.title ?: s.track?.title ?: "Your music, with directions."
        artist.text=when {
            s.track==null -> "Choose a playlist or folder to get started"
            AppState.demo -> "LibreAuto · Demo track"
            !presentation?.artist.isNullOrBlank() -> presentation!!.artist
            !s.track.artist.isBlank() -> s.track.artist
            else -> "Local audio · VLC"
        }
        val navigationArtwork=Library.hintPlacement(this).navigationArtwork(AppState.guidance)
        val bitmap=if(navigationArtwork)AppState.artwork else presentation?.artwork
        if(renderedBitmap!==bitmap){cover.setImageBitmap(bitmap);renderedBitmap=bitmap}
        cover.visibility=if(bitmap!=null)View.VISIBLE else View.GONE
        sleeve.visibility=if(bitmap==null)View.VISIBLE else View.GONE
        cover.contentDescription=if(navigationArtwork)"Navigation artwork sent to your stereo" else "Album artwork"
        play.setImageDrawable(PlayerIcon(if(s.playing)"pause" else "play",PlayerUi.background));play.contentDescription=if(s.playing)"Pause" else "Play"
        seek.isEnabled=s.durationMs>0
        if(!dragging){seek.progress=if(s.durationMs>0)(s.positionMs*1000/s.durationMs).toInt().coerceIn(0,1000)else 0;elapsed.text=time(s.positionMs)}
        duration.text=time(s.durationMs)
        val tracks=Library.tracks(this);queue.text="Queue · ${tracks.size} ${if(tracks.size==1)"track" else "tracks"}"
        shuffle.text=if(Library.shuffle(this))"Shuffle on" else "In order"
        shuffle.contentDescription=if(Library.shuffle(this))"Shuffle is on. Tap to play in order." else "Playing in order. Tap to shuffle."
        error.text=AppState.error;error.visibility=if(AppState.error.isNullOrBlank())View.GONE else View.VISIBLE
    }
    private fun showQueue() {
        val tracks=Library.tracks(this)
        if(tracks.isEmpty()){openLibrary();return}
        val list=ListView(this).apply {divider=null;setPadding(dp(12),0,dp(12),0)}
        val names=tracks.mapIndexed{i,t->"${if(AppState.playback.queueIndex==i && AppState.playback.track?.uri==t.uri)"▶  " else ""}${i+1}. ${t.title}"}
        list.adapter=object:ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names){
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View = (super.getView(position,convertView,parent) as TextView).apply{
                setTextColor(PlayerUi.ink);textSize=16f;minHeight=dp(58);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            }
        }
        val dialog=AlertDialog.Builder(this).setTitle("Current queue").setView(list)
            .setPositiveButton("Music library"){_,_->openLibrary()}.setNegativeButton("Close",null)
            .setNeutralButton("Clear queue"){_,_->clearQueue()}.create()
        list.setOnItemClickListener{_,_,index,_->requestNotificationPermission();command(BluetoothAutoService.PLAY_INDEX,index);dialog.dismiss()}
        dialog.show()
    }
    private fun clearQueue() {
        AlertDialog.Builder(this).setTitle("Clear current queue?")
            .setMessage("Your saved playlists, folders and original audio files stay available in Music library.")
            .setPositiveButton("Clear"){_,_->
                Thread({
                    val result=runCatching {
                        // Preserve the legacy queue as a saved playlist before its first clear.
                        org.bluetoothauto.playback.MusicLibraryStore.load(this)
                        Library.save(this,emptyList())
                    }
                    runOnUiThread {
                        if(result.isSuccess){
                            if(AppState.running || org.openandroidauto.integration.ProjectionIntegration.isSessionActive())command(BluetoothAutoService.QUEUE_CHANGED)
                            if(!isFinishing && !isDestroyed)render()
                        } else if(!isFinishing && !isDestroyed)AlertDialog.Builder(this)
                            .setTitle("Queue kept").setMessage("The music library could not be saved. Your current queue is unchanged.")
                            .setPositiveButton("OK",null).show()
                    }
                },"music-queue-clear").start()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun toggleShuffle(){
        Library.shuffle(this,!Library.shuffle(this));if(AppState.running || org.openandroidauto.integration.ProjectionIntegration.isSessionActive())command(BluetoothAutoService.SHUFFLE);render()
    }
    private fun openLibrary(){startActivity(Intent(this,MusicLibraryActivity::class.java))}
    private fun requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),51)}
    private fun requestNormalPermissions():Boolean {
        val p=mutableListOf<String>()
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){p.add(Manifest.permission.ACCESS_FINE_LOCATION);p.add(Manifest.permission.ACCESS_COARSE_LOCATION)}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS)
        if(p.isEmpty())return false
        requestPermissions(p.toTypedArray(),52);return true
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,results:IntArray){
        super.onRequestPermissionsResult(code,permissions,results)
        if(AppState.running)command(BluetoothAutoService.SETTINGS)
        if(pendingMaps)openNavigator()
    }
    private fun openNavigator(){
        pendingMaps=false
        command(BluetoothAutoService.LIVE)
        if(Library.navigator(this)=="OPEN")startActivity(Intent(this,OwnMapsActivity::class.java))
        else {
            val launch=packageManager.getLaunchIntentForPackage(MapsNotificationListener.MAPS_PACKAGE)
            if(launch!=null)startActivity(launch)else Toast.makeText(this,"Install Google Maps, or choose Open maps in Settings.",Toast.LENGTH_LONG).show()
        }
    }
    private fun command(action:String,index:Int=0)=BluetoothAutoService.command(this,action,index)
    private fun time(n:Long)="${n.coerceAtLeast(0)/60000}:${"%02d".format(n.coerceAtLeast(0)/1000%60)}"
}
