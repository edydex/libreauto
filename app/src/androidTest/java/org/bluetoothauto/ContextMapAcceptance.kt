// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.SystemClock
import org.bluetoothauto.artwork.MapArtworkRenderer
import org.bluetoothauto.artwork.TurnArtworkPolicy
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GeoPoint
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** Public synthetic rendering fixture; separate from acceptance of the live user's navigation. */
internal object ContextMapAcceptance {
    fun run(test:Instrumentation):String {
        var renderer:MapArtworkRenderer?=null
        var failure:Throwable?=null
        val context=test.targetContext
        val guidance=Guidance("Turn left toward Example Street",150.0,"150 m")
        fun point(offset:Double=0.0)=Position(47.6062+offset,-122.3321,0f,3f,receivedAt=SystemClock.elapsedRealtime(),speedMps=0f)
        fun render(position:Position,geometry:TurnGeometry?,status:String,file:String?=null){
            val done=CountDownLatch(1)
            test.runOnMainSync {
                if(renderer==null)renderer=MapArtworkRenderer(context,allowCurrentContext=true)
                renderer!!.render(position,guidance,"METRIC",geometry){bitmap,current->
                    if(current==status){
                        try {
                            check(bitmap.width==512 && bitmap.height==512){"Unexpected context image size"}
                            if(geometry==null){
                                var teal=0
                                for(y in 48 until 368)for(x in 0 until 512)if(bitmap.getPixel(x,y)==0xff00bba2.toInt())teal++
                                check(teal>=50){"Actual-position puck missing from context map"}
                            }else{
                                val blue=0xff1677ff.toInt()
                                val frame=TurnArtworkPolicy.frame(position,geometry,guidance.instruction)
                                fun screen(point:GeoPoint):Pair<Double,Double>{
                                    fun mercatorY(latitude:Double):Double{
                                        val sine=sin(Math.toRadians(latitude))
                                        return .5-ln((1+sine)/(1-sine))/(4*Math.PI)
                                    }
                                    var dx=(point.longitude-frame.center.longitude)/360
                                    if(dx>.5)dx-=1
                                    if(dx<-.5)dx+=1
                                    val dy=mercatorY(point.latitude)-mercatorY(frame.center.latitude)
                                    val angle=Math.toRadians(frame.bearingDegrees)
                                    val scale=512*2.0.pow(frame.zoom)
                                    return 256+(cos(angle)*dx+sin(angle)*dy)*scale to
                                        256+(-sin(angle)*dx+cos(angle)*dy)*scale
                                }
                                fun hasBlue(x:Double,y:Double):Boolean{
                                    val px=x.roundToInt();val py=y.roundToInt()
                                    return ((py-4).coerceAtLeast(0)..(py+4).coerceAtMost(367)).any{row->
                                        ((px-4).coerceAtLeast(0)..(px+4).coerceAtMost(511)).any{column->
                                            bitmap.getPixel(column,row)==blue
                                        }
                                    }
                                }
                                val anchor=screen(geometry.junction)
                                check(abs(anchor.first-256.0)<.1 && abs(anchor.second-184.0)<.1){
                                    "Actual junction was not centered above the footer"
                                }
                                check(hasBlue(anchor.first,anchor.second)){"Native route missing at the centered junction"}
                                val path=frame.path.map(::screen)
                                check(path.all{(x,y)->x in 22.0..490.0 && y in 16.0..352.0}){
                                    "Complete supplied departure did not fit above the footer"
                                }
                                // The whole departure now fits with an inset endpoint; it must
                                // no longer be required to run off the left edge of the image.
                                check(hasBlue(path.last().first,path.last().second)){
                                    "Native route did not reach its supplied departure endpoint"
                                }
                                path.zipWithNext().forEach{(a,b)->
                                    val samples=ceil(hypot(b.first-a.first,b.second-a.second)/2).toInt().coerceAtLeast(1)
                                    check((0..samples).all{step->
                                        val fraction=step.toDouble()/samples
                                        hasBlue(a.first+(b.first-a.first)*fraction,a.second+(b.second-a.second)*fraction)
                                    }){"Native route has a gap inside the complete fitted turn"}
                                }
                                val bottom=(360 until 368).any{y->(0 until 512).any{x->bitmap.getPixel(x,y)==blue}}
                                check(bottom){"Validated approach did not continue to the visible map edge"}
                            }
                            if(file!=null)File(context.cacheDir,file).outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                        }catch(error:Throwable){failure=error}
                        done.countDown()
                    }
                }
            }
            check(done.await(30,TimeUnit.SECONDS)){"Native map never reached requested mode: $status"}
            failure?.let{throw it}
        }
        try {
            // Exercise the product constructor itself: an unresolved turn stays an arrow even
            // with fresh accurate GPS, and does not schedule a native location-only map.
            test.runOnMainSync {
                val product=MapArtworkRenderer(context)
                try {
                    val position=point()
                    var callbacks=0
                    check(!product.needsTurnMap(position,guidance,null)){"Unresolved product turn requested a context map"}
                    product.render(position,guidance,"METRIC"){bitmap,status->
                        check(bitmap.width==512 && bitmap.height==512)
                        check(status=="Turn location unconfirmed · direction card"){"Product renderer replaced an unresolved turn with a location map"}
                        callbacks++
                    }
                    check(callbacks==1){"Unresolved product direction card was not published"}
                    check(product.diagnostics().snapshotRequests==0L){"Product renderer started a context snapshot"}
                    check(!product.needsTurnMap(position,guidance,null)){"Unresolved product turn kept requesting context work"}
                }finally{product.close()}
            }
            render(point(),null,"Current area","current-area-native.png")
            test.runOnMainSync {check(renderer!!.diagnostics().completedSnapshotCount==1L){"Context did not use a native map"}}
            render(point(.00005),null,"Current area")
            test.runOnMainSync {check(renderer!!.diagnostics().snapshotRequests==1L){"Small position update repainted base map"}}
            val junction=GeoPoint(47.60635,-122.3321)
            val geometry=TurnGeometry(listOf(GeoPoint(47.6001,-122.3321),junction,GeoPoint(47.60635,-122.34145)),junction,null,"matched","public-context-promotion")
            render(point(.00005),geometry,"Upcoming turn","current-area-promoted-native.png")
            test.runOnMainSync {
                val stats=renderer!!.diagnostics()
                check(stats.completedSnapshotCount==2L){"Confirmed turn was not promoted"}
                check(stats.nativeRouteSnapshots==1L){"Confirmed turn did not use native route source/layers"}
            }
            return "product unresolved turn stays arrow; explicit diagnostic current-area map; cached small move; confirmed-turn promotion"
        } finally {test.runOnMainSync{renderer?.close()}}
    }
}
