// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.model

import android.graphics.Bitmap
import android.os.SystemClock

data class Track(val uri: String, val title: String, val artist: String = "")

data class Guidance(
    val instruction: String,
    val distanceMeters: Double? = null,
    val distanceLabel: String = "",
    val icon: Bitmap? = null,
    val receivedAt: Long = SystemClock.elapsedRealtime(),
    val source: String = "Google Maps",
    val demo: Boolean = false
)

data class Position(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float? = null,
    val accuracy: Float = 0f,
    val receivedAt: Long = SystemClock.elapsedRealtime(),
    val demo: Boolean = false,
    val speedMps: Float? = null
)

data class PlaybackState(
    val track: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    val error: String? = null
)
