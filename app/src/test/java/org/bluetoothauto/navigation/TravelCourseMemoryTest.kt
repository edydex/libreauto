// SPDX-License-Identifier: GPL-3.0-or-later
package org.bluetoothauto.navigation

import org.bluetoothauto.model.Position
import org.junit.Assert.*
import org.junit.Test

class TravelCourseMemoryTest {
    private fun fix(time:Long=1000, east:Double=0.0, north:Double=0.0, bearing:Float?=null,
        speed:Float?=null, accuracy:Float=3f)=Position(47.0+north/111_195.08,
        -122.0+east/(111_195.08*kotlin.math.cos(Math.toRadians(47.0))),bearing,accuracy,time,speedMps=speed)
    private fun seed(memory:TravelCourseMemory)=memory.update(fix(bearing=90f,speed=10f))

    @Test fun stopAndBearinglessNetworkFixDoNotEraseRecentCourse(){
        val memory=TravelCourseMemory();seed(memory)
        assertEquals(90f,memory.update(fix(4000,east=4.0,speed=0f)).bearing!!,0f)
        assertEquals(90f,memory.update(fix(7000,east=5.0)).bearing!!,0f)
    }
    @Test fun stationaryUpdatesDoNotKeepOldCourseAliveForever(){
        val memory=TravelCourseMemory();seed(memory)
        assertNotNull(memory.update(fix(1000+TravelCourseMemory.MAX_MEMORY_AGE,speed=0f)).bearing)
        assertNull(memory.update(fix(1001+TravelCourseMemory.MAX_MEMORY_AGE,speed=0f)).bearing)
    }
    @Test fun stationaryDistanceBoundPreventsRemoteReuse(){
        val memory=TravelCourseMemory();seed(memory)
        assertNull(memory.update(fix(11_000,east=65.0,speed=0f)).bearing)
        assertNull(memory.update(fix(12_000,east=65.0,speed=0f)).bearing)
    }
    @Test fun derivesEastwardCourseFromAccurateMovementWithoutDeviceBearing(){
        val memory=TravelCourseMemory();memory.update(fix())
        assertEquals(90f,memory.update(fix(4000,east=20.0)).bearing!!,.1f)
    }
    @Test fun accumulatesAdequateDisplacementAcrossSmallMovingSamples(){
        val memory=TravelCourseMemory();memory.update(fix())
        assertNull(memory.update(fix(2000,east=3.0)).bearing)
        assertNull(memory.update(fix(3000,east=6.0)).bearing)
        assertEquals(90f,memory.update(fix(4000,east=9.0)).bearing!!,.1f)
    }
    @Test fun combinedAccuracyMustBeExceededBeforeDeriving(){
        val memory=TravelCourseMemory();memory.update(fix(accuracy=10f))
        assertNull(memory.update(fix(4000,east=12.0,accuracy=10f)).bearing)
        assertEquals(90f,memory.update(fix(6000,east=25.0,accuracy=10f)).bearing!!,.1f)
    }
    @Test fun explicitStationaryFixDoesNotDeriveHeadingFromDrift(){
        val memory=TravelCourseMemory();memory.update(fix(speed=0f))
        assertNull(memory.update(fix(4000,east=15.0,speed=0f)).bearing)
        assertNull(memory.update(fix(7000,east=30.0,speed=0f)).bearing)
    }
    @Test fun teleportDropsCourseEvenWhenInjectedBearingClaimsMotion(){
        val memory=TravelCourseMemory();seed(memory)
        assertNull(memory.update(fix(2000,east=1000.0,bearing=180f,speed=10f)).bearing)
        assertEquals(180f,memory.update(fix(4000,east=1000.0,bearing=180f,speed=10f)).bearing!!,0f)
    }
    @Test fun outOfOrderFixCannotReplaceKnownCourseOrAnchor(){
        val memory=TravelCourseMemory();seed(memory)
        assertNull(memory.update(fix(999,bearing=180f,speed=10f)).bearing)
        assertNull(memory.update(fix(1000,bearing=180f,speed=10f)).bearing)
        assertEquals(90f,memory.update(fix(4000,speed=0f)).bearing!!,0f)
    }
    @Test fun invalidFixDoesNotPoisonNextValidStationaryFix(){
        val memory=TravelCourseMemory();seed(memory)
        assertNull(memory.update(fix(2000,bearing=180f,speed=10f,accuracy=Float.NaN)).bearing)
        assertNull(memory.update(fix(2500,bearing=180f,speed=10f,accuracy=26f)).bearing)
        assertNull(memory.update(fix(3000,bearing=180f,speed=Float.POSITIVE_INFINITY)).bearing)
        assertEquals(90f,memory.update(fix(4000,speed=0f)).bearing!!,0f)
    }
    @Test fun suppliedStationaryOrInvalidCourseCannotCreateMemory(){
        val memory=TravelCourseMemory()
        assertNull(memory.update(fix(bearing=90f,speed=0f)).bearing)
        assertNull(memory.update(fix(2000,bearing=Float.NaN,speed=10f)).bearing)
        assertNull(memory.update(fix(3000,bearing=360f,speed=10f)).bearing)
    }
    @Test fun oldDerivationAnchorCannotInventCourseFromLongGap(){
        val memory=TravelCourseMemory();memory.update(fix())
        assertNull(memory.update(fix(62_000,east=100.0)).bearing)
    }
    @Test fun resetPreventsPreviousSessionCourseFromLeaking(){
        val memory=TravelCourseMemory();seed(memory);memory.reset()
        assertNull(memory.update(fix(5000,speed=0f)).bearing)
    }
    @Test fun derivesNorthwardCourse(){
        val memory=TravelCourseMemory();memory.update(fix())
        assertEquals(0f,memory.update(fix(4000,north=20.0)).bearing!!,.1f)
    }
    @Test fun explicitlyTrustedParkedSimulatorCourseDoesNotRequireDriving(){
        val memory=TravelCourseMemory()
        assertEquals(90f,memory.update(fix(bearing=90f,speed=0f),trustedStationaryCourse=true).bearing!!,0f)
        assertEquals(90f,memory.update(fix(4000,speed=0f)).bearing!!,0f)
        assertNull(TravelCourseMemory().update(fix(bearing=90f,speed=0f)).bearing)
    }
    @Test fun trustedCourseStillRejectsInvalidBearingSpeedOrAccuracy(){
        for(invalid in listOf(fix(bearing=Float.NaN,speed=0f),fix(bearing=360f,speed=0f),
            fix(bearing=-1f,speed=0f),fix(bearing=90f,speed=-1f),fix(bearing=90f,speed=Float.NaN),
            fix(bearing=90f,speed=Float.POSITIVE_INFINITY),fix(bearing=90f,speed=81f),
            fix(bearing=90f,speed=null),fix(bearing=90f,speed=0f,accuracy=26f))) {
            assertNull(TravelCourseMemory().update(invalid,trustedStationaryCourse=true).bearing)
        }
    }
    @Test fun trustedParkedCourseMemoryStillExpiresAndRejectsOutOfOrderInput(){
        val memory=TravelCourseMemory()
        memory.update(fix(bearing=45f,speed=0f),trustedStationaryCourse=true)
        assertNull(memory.update(fix(999,bearing=180f,speed=0f),trustedStationaryCourse=true).bearing)
        assertNull(memory.update(fix(1001+TravelCourseMemory.MAX_MEMORY_AGE,speed=0f)).bearing)
    }
    @Test fun equalTimestampBetterAccuracyPreservesRecentCourse(){
        val memory=TravelCourseMemory()
        memory.update(fix(bearing=90f,speed=10f,accuracy=15f))
        assertEquals(90f,memory.update(fix(speed=0f,accuracy=3f)).bearing!!,0f)
        assertNull(memory.update(fix(speed=0f,accuracy=3f)).bearing)
        assertNull(memory.update(fix(speed=0f,accuracy=10f)).bearing)
        assertEquals(90f,memory.update(fix(4000,speed=0f,accuracy=3f)).bearing!!,0f)
    }
    @Test fun equalTimestampAccuracyRefinementCannotDeriveMotion(){
        val memory=TravelCourseMemory()
        memory.update(fix(accuracy=15f))
        assertNull(memory.update(fix(east=20.0,accuracy=3f)).bearing)
        assertNull(memory.update(fix(4000,east=20.0,accuracy=3f)).bearing)
    }
}
