// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto

import android.app.Instrumentation
import android.app.Notification
import android.os.Bundle
import org.bluetoothauto.artwork.ManeuverGlyph
import org.bluetoothauto.model.Guidance
import org.bluetoothauto.navigation.GoogleMapsNotificationParser
import org.bluetoothauto.navigation.GuidanceFormatter
import org.bluetoothauto.navigation.NotificationTextParser
import org.bluetoothauto.navigation.NotificationTurnHint
import org.bluetoothauto.navigation.NotificationTurnHint.Kind
import java.util.Locale

/** Bounded Android-runtime examples; creates notifications locally, never posts them or changes state. */
internal object EnglishManeuverAcceptance {
    private data class Case(val text:String,val arrow:String,val titlePrefix:String,
                            val glyph:ManeuverGlyph,val mapKind:Kind?)

    fun run(test:Instrumentation):Bundle {
        val cases=ArrayList<Case>()
        for(left in listOf(true,false)) {
            val side=if(left)"left" else "right"
            val other=if(left)"right" else "left"
            val arrow=if(left)"←" else "→"
            val prefix=if(left)"<-" else "->"
            val glyph=if(left)ManeuverGlyph.LEFT else ManeuverGlyph.RIGHT
            val kind=if(left)Kind.LEFT else Kind.RIGHT
            val shallowArrow=if(left)"↖" else "↗"
            val shallowPrefix=if(left)"<-\\" else "/->"
            val shallowGlyph=if(left)ManeuverGlyph.SLIGHT_LEFT else ManeuverGlyph.SLIGHT_RIGHT
            fun regular(text:String)=cases.add(Case(text,arrow,prefix,glyph,kind))
            fun shallow(text:String,mapKind:Kind)=cases.add(Case(text,shallowArrow,shallowPrefix,shallowGlyph,mapKind))
            for(lane in listOf("", "Use the $side lane to ","Use the $other lane to ",
                "Use the $side 2 lanes to ","Use the 2 $side lanes to ",
                "Use the $side two lanes to ","Use two $side lanes to ",
                "Use any lane to ","At the traffic lights, use the $other lane to ")) {
                regular("${lane}turn $side onto Main Street")
            }
            for(lane in listOf("either lane","both lanes","the middle lane","the center lane")) {
                regular("Use $lane to turn $side onto Main Street")
            }
            regular("At the traffic lights, turn $side onto Pine Street")
            regular("Use any lane to turn $side onto Exit Street")
            regular("Use the $other lane to turn $side onto Ramp Road")
            regular("Turn sharply $side onto Main Street")
            regular("Take the second $side onto Main Street")
            shallow("Use the $other lane to turn slightly $side onto Main Street",if(left)Kind.SLIGHT_LEFT else Kind.SLIGHT_RIGHT)
            shallow("Bear $side onto Main Street",if(left)Kind.SLIGHT_LEFT else Kind.SLIGHT_RIGHT)
            shallow("Use the $other lane to keep $side onto Main Street",if(left)Kind.KEEP_LEFT else Kind.KEEP_RIGHT)
            shallow("Use the $other lane to merge $side onto I-5 N",if(left)Kind.MERGE_LEFT else Kind.MERGE_RIGHT)
            shallow("Use the $side lane to merge onto I-5 N",if(left)Kind.MERGE_LEFT else Kind.MERGE_RIGHT)
            shallow("Use the $other lane to take exit 25 on the $side for I-5 N",if(left)Kind.EXIT_LEFT else Kind.EXIT_RIGHT)
            shallow("Take the I-5 N ramp on the $side toward Test City",if(left)Kind.RAMP_LEFT else Kind.RAMP_RIGHT)
            shallow("Use the $side lane to take the I-5 N ramp toward Test City",if(left)Kind.RAMP_LEFT else Kind.RAMP_RIGHT)
        }
        cases.add(Case("Use the middle lane to take the I-5 N ramp toward Test City",
            "NAV","NAV",ManeuverGlyph.UNKNOWN,Kind.RAMP))
        cases.add(Case("  uSe   ThE   2   RIGHT   lanes TO TURN LEFT onto Main Street  ",
            "←","<-",ManeuverGlyph.LEFT,Kind.LEFT))
        val unsupported=listOf("Use the left or right lanes to turn left onto Main Street",
            "Use any lane to turn onto Main Street","Use the left lane","Use the left lane to see Right Avenue",
            "Use the 0 left lanes to turn left onto Main Street","Use the 7 right lanes to turn right onto Main Street",
            "Turn left or right onto Main Street","Turn left onto Main Street then turn right onto Pine Street",
            "Turn left onto Main Street; turn right onto Pine Street",
            "Turn left onto Main Street and turn right onto Pine Street","Turn leftward onto Main Street")
        unsupported.forEach { cases.add(Case(it,"NAV","NAV",ManeuverGlyph.UNKNOWN,null)) }
        check(cases.size<=80){"Native English fixture exceeded its bounded example count"}
        val parser=GoogleMapsNotificationParser(test.targetContext)
        var mapCases=0;var neutralCases=0
        cases.forEachIndexed { index,case ->
            val parsed=checkNotNull(NotificationTextParser.parse(listOf("250 ft · ${case.text}"),true,true,Locale.US)) {
                "English case $index was dropped as a navigation notification"
            }
            check(NotificationTextParser.arrow(parsed.instruction)==case.arrow){"Wrong title arrow in English case $index"}
            check(ManeuverGlyph.fromInstruction(parsed.instruction)==case.glyph){"Wrong artwork glyph in English case $index"}
            check(NotificationTurnHint.parse(parsed.instruction)?.kind==case.mapKind){"Wrong map admission/kind in English case $index"}
            val title=GuidanceFormatter.title(Guidance(parsed.instruction,parsed.distanceMeters,parsed.distanceLabel),"Fixture song","IMPERIAL")
            check(title.startsWith("${case.titlePrefix} 250 ft · ")){"Wrong stereo title prefix in English case $index"}
            val notification=Notification.Builder(test.targetContext,"native-english-fixture")
                .setSmallIcon(R.drawable.ic_notification).setCategory(Notification.CATEGORY_NAVIGATION)
                .setOngoing(true).setContentTitle("250 ft · ${case.text}").build()
            val native=checkNotNull(parser.parse(notification)){"Android notification parser dropped English case $index"}
            check(NotificationTextParser.arrow(native.instruction)==case.arrow){"Android notification arrow differed in English case $index"}
            check(ManeuverGlyph.fromInstruction(native.instruction)==case.glyph){"Android notification glyph differed in English case $index"}
            check(NotificationTurnHint.parse(native.instruction)?.kind==case.mapKind){"Android notification map kind differed in English case $index"}
            if(case.mapKind==null)neutralCases++ else mapCases++
        }
        return Bundle().apply {
            putInt("english_cases",cases.size)
            putInt("english_admitted_map_cases",mapCases)
            putInt("english_neutral_cases",neutralCases)
            putInt("english_consumer_checks",cases.size*7)
            putInt("english_local_notifications",cases.size)
            putBoolean("english_state_changed",false)
            putBoolean("english_gps_injected",false)
            putBoolean("english_network_used",false)
            putString("english_maneuver","mirrored/count/any-lane/opposing-lane/road-names/compound rejection; title, artwork and map consumers agree; local Android notifications only")
        }
    }
}
