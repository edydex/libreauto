package org.openandroidauto

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import org.openandroidauto.ui.DashboardView
import org.openandroidauto.ui.CarUi
import java.lang.ref.WeakReference
import org.openandroidauto.integration.ProjectionAvailability

/** Independent phone preview. A projection session has exclusive dashboard ownership. */
class PreviewActivity : Activity() {
    companion object {
        private var active = WeakReference<PreviewActivity>(null)
        /** Both Activity and Service lifecycle entry points execute on the main thread. */
        internal fun closeBeforeProjection() {
            active.get()?.let { preview -> preview.releaseDashboard(); preview.finish() }
            active.clear()
        }
    }
    private var dashboard: DashboardView? = null
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProjectionAvailability.unavailableReason()?.let { reason ->
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (ServiceState.projectionActive) {
            Toast.makeText(this, "Stop the car connection before opening preview.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        active.get()?.takeIf { it !== this }?.let { it.releaseDashboard(); it.finish() }
        active = WeakReference(this)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(CarUi.BACKGROUND) }
        val toolbar=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(12,0,12,0) }
        toolbar.addView(CarUi.text(this,"Dashboard preview",16f,CarUi.TEXT),LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        toolbar.addView(CarUi.button(this,"Exit preview",false).apply {
            contentDescription="openauto.preview.exit"
            setOnClickListener { finish() }
        })
        layout.addView(toolbar,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT))
        dashboard=DashboardView(this).also { layout.addView(it,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f)) }
        setContentView(layout)
        if(Build.VERSION.SDK_INT>=33){
            backCallback=OnBackInvokedCallback { dashboard?.handleBackNavigation() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,it)
            }
        }
    }
    @Deprecated("Preview Back navigates locally; Exit preview is explicit")
    override fun onBackPressed() { dashboard?.handleBackNavigation() }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if(event.keyCode==android.view.KeyEvent.KEYCODE_BACK || event.keyCode==android.view.KeyEvent.KEYCODE_ESCAPE)
            return dashboard?.dispatchKeyEvent(event) ?: true
        return super.dispatchKeyEvent(event)
    }
    override fun onStart() { super.onStart(); dashboard?.onStart() }
    override fun onStop() { dashboard?.onStop(); super.onStop() }
    override fun onDestroy() {
        if(Build.VERSION.SDK_INT>=33)backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        backCallback=null
        releaseDashboard()
        if (active.get() === this) active.clear()
        super.onDestroy()
    }
    private fun releaseDashboard() {
        dashboard?.close()
        dashboard = null
    }
}
