// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import org.bluetoothauto.navigation.TurnRoadStore
import java.util.concurrent.Executors

/** Phone-only management of bounded local road data. Import does not retain document access. */
class PreparedMapsActivity:Activity(){
    private val worker=Executors.newSingleThreadExecutor()
    private lateinit var status:TextView
    private lateinit var importButton:Button
    private lateinit var clearButton:Button
    private var busy=false
    private var closed=false
    private fun dp(value:Int)=PlayerUi.dp(this,value)
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(20));setBackgroundColor(PlayerUi.background)}
        val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        bar.addView(PlayerUi.icon(this,"back","Back to settings"){finish()},LinearLayout.LayoutParams(dp(48),dp(56)))
        bar.addView(PlayerUi.label(this,"Prepared maps",23,true));root.addView(bar)
        status=PlayerUi.label(this,"Checking saved areas…",17,false).apply{setPadding(0,dp(25),0,dp(18))};root.addView(status)
        root.addView(PlayerUi.label(this,"Downloaded roads are saved for seven days. Turn pictures stay fixed while their distance updates. Open maps can prepare the next turns in advance.",15,false,PlayerUi.muted))
        importButton=Button(this).apply{text="Import saved map area";isAllCaps=false;setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT)}}
        root.addView(importButton,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(26)})
        clearButton=Button(this).apply{text="Clear saved road areas";isAllCaps=false;setOnClickListener{
            AlertDialog.Builder(this@PreparedMapsActivity).setTitle("Clear saved road areas?")
                .setMessage("Downloaded roads will need to be loaded again. Your music and saved places stay on the phone.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear"){_,_->runJob{TurnRoadStore(applicationContext).clear();notifyChanged();"Saved road areas cleared"}}.show()
        }}
        root.addView(clearButton,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(10)})
        root.addView(PlayerUi.label(this,"Google Maps supplies the current turn through its notification. Saved road areas let us build that turn’s picture without waiting for another road-data download. Map backgrounds may still need an internet connection.",14,false,PlayerUi.muted).apply{setPadding(0,dp(22),0,0)})
        setContentView(root);refresh()
    }
    override fun onActivityResult(request:Int,result:Int,data:Intent?){
        super.onActivityResult(request,result,data)
        if(request!=IMPORT || result!=RESULT_OK)return
        val uri=data?.data ?: return
        runJob{
            val value=readDocument(uri)
            TurnRoadStore(applicationContext).importArea(value)
            notifyChanged()
            "Map area ready"
        }
    }
    private fun readDocument(uri:Uri):String{
        check(uri.scheme=="content")
        return contentResolver.openInputStream(uri)!!.use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=TurnRoadStore.MAX_IMPORT_BYTES);out.write(buffer,0,n)}
            out.toString("UTF-8")
        }
    }
    private fun notifyChanged(){runOnUiThread{if(AppState.running)BluetoothAutoService.command(this,BluetoothAutoService.PREPARED_MAPS_CHANGED)}}
    private fun runJob(work:()->String){
        if(busy)return
        busy=true;controls(false);status.text="Preparing map area…"
        worker.execute{
            val message=try{work()}catch(_:Exception){"Couldn’t read this map area. Choose a BluetoothAuto map-area file downloaded within the last seven days."}
            runOnUiThread{if(!closed){busy=false;controls(true);Toast.makeText(this,message,Toast.LENGTH_LONG).show();refresh()}}
        }
    }
    private fun controls(enabled:Boolean){importButton.isEnabled=enabled;clearButton.isEnabled=enabled}
    private fun refresh(){
        worker.execute{
            val summary=try{TurnRoadStore(applicationContext).summary()}catch(_:Exception){null}
            runOnUiThread{if(!closed && !busy){
                val count=summary?.areaCount ?: 0
                status.text=if(count==0)"No saved road areas yet" else "$count saved road ${if(count==1)"area" else "areas"} · ready for turn previews"
                clearButton.isEnabled=count>0
            }}
        }
    }
    override fun onDestroy(){closed=true;worker.shutdownNow();super.onDestroy()}
    companion object{private const val IMPORT=81}
}
