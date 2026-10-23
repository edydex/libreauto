// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.artwork

import org.bluetoothauto.model.Position
import org.bluetoothauto.model.TurnGeometry
import org.bluetoothauto.navigation.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class TurnRouteStyleTest {
    @Test fun nativeLinePreservesFullValidatedRouteBeyondCameraApproachWindow() {
        val junction=GeoPoint(47.6,-122.3)
        val points=listOf(GeoPoint(47.59,-122.3),GeoPoint(47.599,-122.3),junction,
            GeoPoint(47.601,-122.3),GeoPoint(47.602,-122.299),GeoPoint(47.603,-122.298))
        val geometry=TurnGeometry(points,junction,30.0,"matched","native-full-turn")
        val frame=TurnArtworkPolicy.frame(Position(47.59,-122.3,accuracy=5f,receivedAt=1_000),geometry)
        val native=checkNotNull(TurnRouteStyle.route(frame)).coordinates()
        assertEquals(points.size,native.size)
        points.zip(native).forEach { (point,coordinate) ->
            assertEquals(point.latitude,coordinate.latitude(),0.0)
            assertEquals(point.longitude,coordinate.longitude(),0.0)
        }
        assertTrue(frame.path.first().latitude>points.first().latitude)
    }

    @Test fun unresolvedCurrentAreaHasNoNativeRouteSource() {
        val frame=TurnArtworkPolicy.frame(Position(47.6,-122.3,accuracy=5f,receivedAt=1_000),null)
        assertNull(TurnRouteStyle.route(frame))
    }
}
