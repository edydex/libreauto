// SPDX-License-Identifier: GPL-3.0-or-later
package org.openandroidauto.sms

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.widget.*
import org.openandroidauto.ServiceState
import org.openandroidauto.calls.CallContact
import org.openandroidauto.calls.CallPolicy
import org.openandroidauto.calls.ContactsRepository
import org.openandroidauto.notifications.ReplyTurnGate
import org.openandroidauto.ui.CarTextKeyboard
import org.openandroidauto.ui.CarUi
import org.openandroidauto.ui.PhoneActivityLauncher
import java.io.Closeable

/** New carrier SMS is separate from replying through a notification's app-owned action. */
class SmsPanel(context:Context,private val listener:Listener):LinearLayout(context),Closeable {
    interface Listener {
        fun onStatus(text:String)
        fun onDictate(token:Long):Boolean
        fun onCancelDictation(token:Long)
        fun onClose()
        fun onComposerOpened()
        fun onFlowChanged()
    }
    private val repository=ContactsRepository(context){SmsAccess.canRead(context)}
    private val gate=SmsSendGate()
    private val turns=ReplyTurnGate()
    private val gateway=AndroidSmsGateway(context)
    private val query=EditText(context)
    private val notice=CarUi.text(context,"",15f,CarUi.TEXT_MUTED)
    private val content=LinearLayout(context).apply{orientation=VERTICAL}
    private val scroller=ScrollView(context).apply{
        clipToPadding=false;setPadding(0,0,0,dp(76));addView(content)
    }
    private val draft=EditText(context).apply{
        textSize=20f;minLines=2;maxLines=4;gravity=Gravity.TOP
        hint="Dictate a message, then review it";setTextColor(CarUi.TEXT);setHintTextColor(CarUi.TEXT_MUTED)
        setShowSoftInputOnFocus(false);filters=arrayOf(InputFilter.LengthFilter(SmsSendGate.MAX_BODY))
        background=CarUi.rounded(context,CarUi.SURFACE_RAISED,14);setPadding(dp(12),dp(10),dp(12),dp(10))
    }
    private var keyboard:CarTextKeyboard?=null
    private var active=false
    private var projection=false
    private var closed=false
    private var generation=0L
    private var ticket:SmsSendGate.Ticket?=null
    private var bodyOpen=false
    private var sending=false
    private var access:Closeable?=null
    init {
        orientation=VERTICAL;setBackgroundColor(CarUi.BACKGROUND)
        val toolbar=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        toolbar.addView(button("Back"){listener.onClose()},LayoutParams(dp(68),dp(48)))
        query.apply{
            hint="New SMS · contact name";textSize=18f;setTextColor(CarUi.TEXT);setHintTextColor(CarUi.TEXT_MUTED)
            setSingleLine(true);setShowSoftInputOnFocus(false);filters=arrayOf(InputFilter.LengthFilter(CallPolicy.MAX_QUERY))
            setOnClickListener{showKeyboard()}
            setOnTouchListener{v,e->if(e.actionMasked==MotionEvent.ACTION_UP&&e.x>=0&&e.x<v.width&&e.y>=0&&e.y<v.height)v.performClick();false}
            addTextChangedListener(object:TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(!active)return;invalidateDraft();content.removeAllViews();message("Choose Find to search contacts")}
                override fun afterTextChanged(s:Editable?){}
            })
        }
        toolbar.addView(query,LayoutParams(0,dp(48),1f))
        toolbar.addView(button("Find"){load(false)},LayoutParams(dp(56),dp(48)))
        toolbar.addView(button("Favorites"){load(true)},LayoutParams(dp(90),dp(48)))
        addView(toolbar)
        notice.setPadding(dp(8),dp(5),dp(8),dp(5));addView(notice)
        addView(scroller,LayoutParams(LayoutParams.MATCH_PARENT,0,1f))
        draft.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){}
            override fun afterTextChanged(s:Editable?){cancelVoiceReply();ticket?.let{gate.setDraft(it,s?.toString()?: "")}}
        })
    }
    fun start(activeProjection:Boolean,search:String="") {
        if(closed)return
        projection=activeProjection;active=true
        access?.close();access=SmsAccess.observe(context){if(active){invalidateDraft();load(false)}}
        query.setText(CallPolicy.searchQuery(search)?: "");load(search.isBlank())
    }
    fun stop(){active=false;projection=false;access?.close();access=null;invalidateDraft();repository.cancel();hideKeyboard();content.removeAllViews();query.setText("")}
    override fun close(){if(closed)return;stop();closed=true;repository.close();gateway.close()}
    fun hasPendingFlow()=active&&!closed&&!sending&&(ticket!=null||query.length()>0)
    fun handleBackNavigation():Boolean {
        if(!active||closed||keyboard?.visibility!=VISIBLE)return false
        hideKeyboard();query.clearFocus();return true
    }
    fun hasReplyTarget()=active&&!closed&&bodyOpen&&!sending&&ticket?.let{gate.confirmed(it)}==true&&SmsAccess.canSend(context)
    fun showReplyStatus(text:String):Boolean{if(!active||closed)return false;notice.text=text;return true}
    fun prepareVoiceReply():Long?=if(hasReplyTarget())turns.prepare(generation)else null
    fun cancelVoiceReply(token:Long?=null){val owned=turns.current(generation);turns.cancel(token);if(owned!=null&&(token==null||token==owned))listener.onCancelDictation(owned)}
    fun acceptTranscript(token:Long,text:String):Boolean{
        if(!hasReplyTarget()||!turns.consume(token,generation)||!SmsSendGate.validBody(text))return false
        draft.setText(text);message("Review this number and message, then press Send. Carrier charges may apply.");return true
    }
    fun cancelPending(){
        if(sending){message("SMS was already requested. Check Messages for its result; this app cannot recall it.");return}
        invalidateDraft();repository.cancel();content.removeAllViews();message("SMS cancelled; nothing sent")
    }
    private fun invalidateDraft(){cancelVoiceReply();generation++;gate.invalidate();ticket=null;bodyOpen=false;draft.setText("");listener.onFlowChanged()}
    private fun load(favorites:Boolean){
        if(!active||closed)return
        if(favorites&&query.length()>0)query.text.clear()
        invalidateDraft();hideKeyboard();content.removeAllViews()
        if(!SmsAccess.canRead(context)){
            message("New SMS is off. Enable it on the phone first.")
            content.addView(button("New SMS setup on phone"){try{PhoneActivityLauncher.open(context,Intent(context,SmsSettingsActivity::class.java))}catch(_:RuntimeException){message("Open New SMS setup from Lite's phone screen")}});return
        }
        val safe=CallPolicy.searchQuery(if(favorites)"" else query.text.toString())?:return
        val expected=generation;message("Choose the exact recipient number")
        repository.query(safe,favorites){result->
            if(!active||closed||generation!=expected)return@query
            content.removeAllViews()
            if(result.failure!=null){message("Contacts unavailable. Check SMS setup or try again.");return@query}
            if(result.rows.isEmpty())message("No matching contacts with supported numbers")
            result.rows.forEach{row->content.addView(button("${row.name}\n${row.label} · ${row.number}"){if(generation==expected)select(row)},LayoutParams(LayoutParams.MATCH_PARENT,dp(68)).apply{bottomMargin=dp(6)})}
            listener.onFlowChanged()
        }
    }
    private fun select(row:CallContact){
        if(!active||closed||!SmsAccess.canRead(context)||sending)return
        invalidateDraft();hideKeyboard();content.removeAllViews()
        val sub=SmsAccess.defaultSubscription()
        if(sub<0){message("Choose a default SMS SIM in the phone's SIM settings, then select this contact again.");return}
        val selected=gate.select(row,sub)?:return;ticket=selected
        identity(row)
        content.addView(CarUi.text(context,"Use this exact number for a new carrier SMS? Android's default SMS SIM will be used.",17f,CarUi.TEXT_MUTED))
        val actions=LinearLayout(context)
        actions.addView(button("Cancel"){load(false)},LayoutParams(0,dp(52),1f))
        actions.addView(button("Use this number",true){confirmRecipient(selected)},LayoutParams(0,dp(52),1f))
        content.addView(actions);message("Confirm the recipient before dictating the message");listener.onFlowChanged()
    }
    private fun confirmRecipient(selected:SmsSendGate.Ticket){
        if(!canAct()||!gate.current(selected)||sending)return
        val expected=generation;message("Checking this exact contact number…")
        repository.revalidate(selected.contact){result->
            if(!active||closed||generation!=expected||!gate.current(selected))return@revalidate
            if(!canAct()||result.failure!=null||!gate.confirmRecipient(selected,result.rows.singleOrNull(),SmsAccess.defaultSubscription())){
                invalidateDraft();message("Recipient, access or SMS SIM changed. Choose the recipient again.");return@revalidate
            }
            bodyOpen=true;content.removeAllViews();identity(selected.contact)
            content.addView(draft,LayoutParams(LayoutParams.MATCH_PARENT,dp(88)).apply{topMargin=dp(8);bottomMargin=dp(8)})
            val actions=LinearLayout(context)
            actions.addView(button("Dictate"){requestBody()},LayoutParams(0,dp(52),1f))
            actions.addView(button("Cancel"){cancelPending()},LayoutParams(0,dp(52),1f))
            actions.addView(button("Send",true){send(selected)},LayoutParams(0,dp(52),1f))
            content.addView(actions);listener.onComposerOpened();listener.onFlowChanged();requestBody()
        }
    }
    private fun requestBody(){val token=prepareVoiceReply()?:return;if(!listener.onDictate(token)){cancelVoiceReply(token);message("Set up optional offline dictation on the phone, then choose Dictate.")}}
    private fun send(selected:SmsSendGate.Ticket){
        if(!canAct()||sending||!gate.confirmed(selected))return
        cancelVoiceReply()
        val visible=draft.text.toString()
        if(!SmsSendGate.validBody(visible)){message("Dictate a message first, then review it before Send");return}
        val expected=generation;message("Checking recipient and SMS SIM before Send…")
        repository.revalidate(selected.contact){result->
            if(!active||closed||generation!=expected||!gate.confirmed(selected))return@revalidate
            val fresh=result.rows.singleOrNull()
            if(result.failure!=null){message("Could not verify this contact. Select the recipient again.");invalidateDraft();return@revalidate}
            try{
                val accepted=gate.send(selected,fresh, visible,SmsAccess.defaultSubscription(),projection&&ServiceState.projectionActive,
                    SmsAccess.canSend(context),unlocked(),SmsSendGate.Gateway{dispatch->
                        sending=true;bodyOpen=false;ticket=null;cancelVoiceReply();draft.setText("");content.removeAllViews();listener.onFlowChanged()
                        message("SMS requested. Waiting for Android's carrier result; do not resend.")
                        gateway.send(dispatch){outcome->
                            sending=false
                            if(active&&!closed)message(when(outcome){
                                AndroidSmsGateway.Result.SENT_TO_CARRIER->"SMS sent to the carrier. Delivery is not confirmed."
                                AndroidSmsGateway.Result.FAILED->"Android reported SMS failure. Check your phone's Messages app before retrying."
                                AndroidSmsGateway.Result.TIMED_OUT->"No SMS result received. Check Messages before retrying; it may still be sent."
                                AndroidSmsGateway.Result.UNAVAILABLE->"SMS could not be requested. Check SMS permission and default SIM."
                            });listener.onFlowChanged()
                        }
                    })
                if(!accepted){invalidateDraft();message("Recipient, draft, access or SMS SIM changed. Select the recipient again.")}
            }catch(_:RuntimeException){invalidateDraft();message("SMS could not be requested. Check the phone before retrying.")}
        }
    }
    private fun canAct():Boolean{
        val allowed=active&&!closed&&projection&&ServiceState.projectionActive&&SmsAccess.canSend(context)&&unlocked()
        if(!allowed)message("SMS needs an active car connection, SMS setup and an unlocked phone")
        return allowed
    }
    private fun unlocked()=try{context.getSystemService(KeyguardManager::class.java)?.let{!it.isDeviceLocked&&!it.isKeyguardLocked}==true}catch(_:RuntimeException){false}
    private fun identity(row:CallContact){
        content.addView(CarUi.text(context,"To ${row.name}",24f,CarUi.TEXT))
        content.addView(CarUi.text(context,"${row.label} · ${row.number}",23f,CarUi.ACCENT))
        content.addView(CarUi.text(context,"NEW CARRIER SMS · Android default SMS SIM",13f,CarUi.TEXT_MUTED))
    }
    private fun showKeyboard(){
        if(!active||closed)return
        invalidateDraft();scroller.visibility=GONE;notice.visibility=GONE
        if(keyboard==null)keyboard=CarTextKeyboard(context,query,object:CarTextKeyboard.Listener{
            override fun onFind(){load(false)};override fun onClose(){hideKeyboard()}
        }).also{it.setFloatingControlClearance(76,80);addView(it,LayoutParams(LayoutParams.MATCH_PARENT,0,1f))}
        keyboard?.visibility=VISIBLE;query.requestFocus()
    }
    private fun hideKeyboard(){keyboard?.visibility=GONE;scroller.visibility=VISIBLE;notice.visibility=VISIBLE}
    private fun message(text:String){notice.text=text;listener.onStatus(text)}
    private fun button(text:String,primary:Boolean=false,action:()->Unit)=CarUi.button(context,text,primary).apply{isAllCaps=false;setOnClickListener{action()}}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
