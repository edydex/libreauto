// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.model

import org.bluetoothauto.navigation.GeoPoint
import org.bluetoothauto.navigation.RoutePlan

/** An exact route slice or a uniquely validated OSM junction match, never an invented bend. */
data class TurnGeometry(
    val points:List<GeoPoint>,
    val junction:GeoPoint,
    val approachSpeedLimitKph:Double?=null,
    val confidence:String="route",
    val key:String,
    /** Built-in navigation's complete, already validated route; camera selection is independent. */
    val sourceRoute:RoutePlan?=null,
    /** A real point on this maneuver used to frame a chained ramp's curve and highway join. */
    val artworkFocus:GeoPoint?=null
)
