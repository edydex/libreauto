// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Shared phone controls: a single icon grid, generous hit targets, and no image assets. */
object PlayerUi {
    val background=Color.rgb(16,28,35)
    val card=Color.rgb(25,42,51)
    val ink=Color.rgb(237,243,244)
    val muted=Color.rgb(167,183,189)
    val accent=Color.rgb(99,217,194)
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density+.5f).toInt()
    fun shape(c:Context,color:Int,radius:Int=18)=GradientDrawable().apply { setColor(color);cornerRadius=dp(c,radius).toFloat() }
    fun label(c:Context,text:String,size:Int=16,bold:Boolean=false,color:Int=ink)=TextView(c).apply {
        this.text=text;textSize=size.toFloat();setTextColor(color)
        if(bold)setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(c,2).toFloat(),1f)
    }
    fun icon(c:Context,name:String,description:String,size:Int=48,primary:Boolean=false,action:()->Unit)=ImageButton(c).apply {
        contentDescription=description;setImageDrawable(PlayerIcon(name,if(primary)PlayerUi.background else ink))
        scaleType=ImageView.ScaleType.FIT_CENTER
        val padding=if(size>60)22 else 12
        setPadding(dp(c,padding),dp(c,padding),dp(c,padding),dp(c,padding))
        background=RippleDrawable(ColorStateList.valueOf(0x3363D9C2),shape(c,if(primary)accent else Color.TRANSPARENT,size/2),null)
        minimumWidth=dp(c,48);minimumHeight=dp(c,48);setOnClickListener{action()}
    }
    fun button(c:Context,text:String,primary:Boolean=false,action:()->Unit)=Button(c).apply {
        this.text=text;isAllCaps=false;textSize=16f;setTextColor(if(primary)PlayerUi.background else ink)
        background=RippleDrawable(ColorStateList.valueOf(0x3363D9C2),shape(c,if(primary)accent else card,14),null)
        minimumHeight=dp(c,50);setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10));setOnClickListener{action()}
    }
}

class PlayerIcon(private val kind:String,private val color:Int):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas) {
        val checkpoint=canvas.save()
        val scale=minOf(bounds.width(),bounds.height())/24f
        canvas.translate(bounds.exactCenterX()-12*scale,bounds.exactCenterY()-12*scale);canvas.scale(scale,scale)
        paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.9f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        fun line(vararg xy:Float) { val p=Path();p.moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2)p.lineTo(xy[i],xy[i+1]);canvas.drawPath(p,paint) }
        fun fill(vararg xy:Float) {paint.style=Paint.Style.FILL;val p=Path();p.moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2)p.lineTo(xy[i],xy[i+1]);p.close();canvas.drawPath(p,paint);paint.style=Paint.Style.STROKE}
        when(kind) {
            "play" -> fill(8f,5f,19f,12f,8f,19f)
            "pause" -> {paint.style=Paint.Style.FILL;canvas.drawRoundRect(6f,5f,10f,19f,1f,1f,paint);canvas.drawRoundRect(14f,5f,18f,19f,1f,1f,paint)}
            "next" -> {fill(5f,5f,15f,12f,5f,19f);line(18f,5f,18f,19f)}
            "previous" -> {fill(19f,5f,9f,12f,19f,19f);line(6f,5f,6f,19f)}
            "map" -> {line(3f,5f,9f,3f,15f,5f,21f,3f,21f,19f,15f,21f,9f,19f,3f,21f,3f,5f);line(9f,3f,9f,19f);line(15f,5f,15f,21f)}
            "settings" -> {
                val p=Path()
                for(i in 0 until 48){val a=(i*Math.PI/24);val r=if(i%6 in 1..4)10f else 8.2f;val x=12+(kotlin.math.sin(a)*r).toFloat();val y=12+(kotlin.math.cos(a)*r).toFloat();if(i==0)p.moveTo(x,y)else p.lineTo(x,y)}
                p.close();canvas.drawPath(p,paint);canvas.drawCircle(12f,12f,3.3f,paint)
            }
            "more" -> {paint.style=Paint.Style.FILL;for(y in listOf(5f,12f,19f))canvas.drawCircle(12f,y,1.7f,paint)}
            "folder" -> {line(3f,7f,3f,19f,21f,19f,21f,6f,12f,6f,10f,4f,3f,4f,3f,7f);line(3f,8f,21f,8f)}
            "playlist" -> {line(4f,5f,17f,5f);line(4f,10f,17f,10f);line(4f,15f,11f,15f);line(17f,19f,17f,13f,22f,12f);paint.style=Paint.Style.FILL;canvas.drawOval(12f,17f,17f,21f,paint)}
            "import" -> {line(12f,3f,12f,15f);line(7f,10f,12f,15f,17f,10f);line(4f,16f,4f,21f,20f,21f,20f,16f)}
            "add" -> {line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
            "queue" -> {line(4f,6f,20f,6f);line(4f,12f,16f,12f);line(4f,18f,13f,18f);fill(18f,15f,23f,18f,18f,21f)}
            "back" -> {line(14f,5f,7f,12f,14f,19f);line(7f,12f,21f,12f)}
            "close" -> {line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
            "chevron" -> line(9f,6f,15f,12f,9f,18f)
            "music" -> {line(9f,17f,9f,5f,20f,3f,20f,15f);line(9f,8f,20f,6f);paint.style=Paint.Style.FILL;canvas.drawOval(3f,15f,9f,20f,paint);canvas.drawOval(14f,13f,20f,18f,paint)}
        }
        canvas.restoreToCount(checkpoint)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Quiet record sleeve when a file does not contain a cover image. */
class RecordSleeve(context:Context):View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas) {
        super.onDraw(c)
        val size=minOf(width,height).toFloat();val cx=width/2f;val cy=height/2f
        p.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),intArrayOf(0xFF304D58.toInt(),0xFF1B303A.toInt()),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),size*.07f,size*.07f,p);p.shader=null
        p.color=0xFF14242D.toInt();c.drawCircle(cx,cy,size*.345f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=size*.0025f;p.color=0xFF324852.toInt()
        for(i in 0..5)c.drawCircle(cx,cy,size*(.19f+i*.025f),p)
        p.style=Paint.Style.FILL;p.color=PlayerUi.accent;c.drawCircle(cx,cy,size*.125f,p)
        p.color=0xFF18333A.toInt();c.drawCircle(cx,cy,size*.022f,p)
        p.color=0x3363D9C2;c.drawCircle(size*.17f,size*.16f,size*.022f,p)
    }
}
