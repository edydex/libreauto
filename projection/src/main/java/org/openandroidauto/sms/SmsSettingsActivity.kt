// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.sms

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*

/** This screen never sends a message or reads the SMS inbox. Permission follows an explicit button. */
class SmsSettingsActivity : Activity() {
    private lateinit var status: TextView
    private var requesting = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,24,28,24) }
        fun text(value:String,size:Float=17f) = TextView(this).also { it.text=value;it.textSize=size;it.setPadding(0,10,0,10);column.addView(it) }
        fun button(value:String,action:()->Unit) {column.addView(Button(this).apply {text=value;setOnClickListener{action()}})}
        text("New SMS setup",27f)
        text("Optional carrier SMS to a contact. Choose a person and exact number, confirm the recipient, dictate a draft, then review and press Send. Carrier charges may apply; longer messages may use several SMS parts. This does not send Telegram or Signal messages.")
        text("Android's chosen default SMS SIM is used. No valid default means no Send: choose the SMS SIM in your phone settings first. A changed default requires selecting the recipient again. This app does not become your default SMS app or read your SMS inbox.")
        status=text("")
        button("Enable new SMS and allow access") {
            if(requesting)return@button
            val missing=required.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
            if(missing.isEmpty()){SmsAccess.setEnabled(this,true);refresh()}
            else {requesting=true;requestPermissions(missing.toTypedArray(),REQUEST)}
        }
        button("Disable new SMS") {SmsAccess.setEnabled(this,false);refresh()}
        text("Some installers or Android versions restrict SMS permission. If Android does not allow it, notification replies still work through the selected messaging app. Do not grant more access than you want.")
        button("Choose default SMS SIM in phone settings") {try {startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))}catch(_:RuntimeException){status.text="Open your phone's SIM settings and choose a default for SMS"}}
        button("App permission settings") {startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",packageName,null)))}
        button("Done") {finish()}
        setContentView(ScrollView(this).apply{addView(column)});refresh()
    }
    override fun onResume(){super.onResume();if(::status.isInitialized)refresh()}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==REQUEST){requesting=false;SmsAccess.setEnabled(this,required.all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED});refresh()}
    }
    private fun refresh(){status.text=when{
        !SmsAccess.enabled(this)->"New SMS is off"
        !SmsAccess.canSend(this)->"New SMS needs Android Contacts and SMS permission, plus SMS support"
        SmsAccess.defaultSubscription()<0->"Choose a default SMS SIM in phone settings before composing"
        else->"New SMS is enabled, using Android's default SMS SIM. Sending always needs your reviewed Send."
    }}
    private companion object {const val REQUEST=62;val required=listOf(Manifest.permission.READ_CONTACTS,Manifest.permission.SEND_SMS)}
}
