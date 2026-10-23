// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** The same native style source/layers are used for visible and prepared turn snapshots. */
internal object TurnRouteStyle {
    const val SOURCE_ID = "bluetoothauto-validated-turn"
    const val CASING_ID = "bluetoothauto-turn-casing"
    const val LINE_ID = "bluetoothauto-turn-line"

    /** Preserve every supplied vertex in travel order; no snapping, extrapolation or new routing. */
    fun route(frame: TurnArtworkPolicy.Frame): LineString? =
        frame.drawPath.takeIf { frame.junction != null && it.size >= 2 }?.let { path ->
            LineString.fromLngLats(path.map { Point.fromLngLat(it.longitude, it.latitude) })
        }

    fun builder(baseStyle: String, frame: TurnArtworkPolicy.Frame): Style.Builder {
        val builder = Style.Builder().fromUri(baseStyle)
        val line = route(frame) ?: return builder
        builder.withSource(GeoJsonSource(SOURCE_ID, line, GeoJsonOptions().withTolerance(0f)))
        builder.withLayer(LineLayer(CASING_ID, SOURCE_ID).withProperties(
            lineColor("#ffffff"), lineWidth(16f), lineCap("round"), lineJoin("round")))
        builder.withLayer(LineLayer(LINE_ID, SOURCE_ID).withProperties(
            lineColor("#1677ff"), lineWidth(10f), lineCap("round"), lineJoin("round")))
        return builder
    }
}
