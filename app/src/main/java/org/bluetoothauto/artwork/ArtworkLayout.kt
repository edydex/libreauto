// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

/** Artwork coordinates are fixed at512px and scale together for Bluetooth thumbnails. */
internal object ArtworkLayout {
    const val MAP_TOP = 0f
    const val MAP_BOTTOM = 368f
    const val ROAD_CENTER_Y = 184.0
    const val ROAD_VISIBLE_HEIGHT = 320.0
    const val DISTANCE_TOP = 400f
    const val DISTANCE_BOTTOM = 452f
    const val DISTANCE_BASELINE = 441f
    const val DISTANCE_TEXT_SIZE = 44f
    const val CREDIT_TOP = 374f
    const val CREDIT_BOTTOM = 398f
    const val CREDIT_BASELINE = 392f
    const val CREDIT_TEXT_SIZE = 18f
    const val CREDIT_BACKGROUND = 0xb8ffffff.toInt()
    const val CREDIT_INK = 0xf0203036.toInt()
    const val INSTRUCTION_TOP = 451f
    const val INSTRUCTION_BOTTOM = 512f
    const val INSTRUCTION_BASELINE = 475f
    const val INSTRUCTION_TEXT_SIZE = 24f

    // OpenFreeMap's fixed Liberty style requires OpenMapTiles and OSM credit;
    // https://openfreemap.org/ documents that OpenFreeMap's own credit is optional.
    const val CREDITS = "© OpenMapTiles · © OpenStreetMap contributors"
}
