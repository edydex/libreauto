// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.model.Position
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Bounded reuse of a real location map, without deriving any turn, route or road alignment. */
internal object CurrentContextPolicy {
    const val REFRESH_MILLIS = 15_000L
    const val MAX_CENTER_DISTANCE_METERS = 100.0
    data class PuckKey(val latitudeE5: Long, val longitudeE5: Long, val courseStep: Int?)

    fun frame(position: Position, now: Long): TurnArtworkPolicy.Frame? =
        if (!valid(position,now)) null else TurnArtworkPolicy.frame(position,null).copy(
            zoom=16.0,bearingDegrees=0.0,markJunction=false)

    fun canDisplay(frame: TurnArtworkPolicy.Frame, position: Position, now: Long): Boolean =
        frame.currentContext && valid(position,now) && ArtworkPolicy.meters(frame.center.latitude,
            frame.center.longitude,position.latitude,position.longitude) <= MAX_CENTER_DISTANCE_METERS

    fun canReuse(frame: TurnArtworkPolicy.Frame, readyAt: Long, position: Position, now: Long): Boolean =
        now-readyAt in 0 until REFRESH_MILLIS && canDisplay(frame,position,now)

    /** Position changes below about a metre and course changes below five degrees share a card. */
    fun puckKey(position: Position?): PuckKey? = position?.let {
        if (!it.latitude.isFinite() || !it.longitude.isFinite()) return@let null
        PuckKey((it.latitude*100_000).roundToLong(),(it.longitude*100_000).roundToLong(),
            TurnArtworkPolicy.movingCourse(it)?.let { course -> (course/5).roundToInt()%72 })
    }

    private fun valid(position: Position, now: Long) = ArtworkPolicy.validPosition(position.latitude,
        position.longitude,position.accuracy.toDouble(),position.receivedAt,now)
}
