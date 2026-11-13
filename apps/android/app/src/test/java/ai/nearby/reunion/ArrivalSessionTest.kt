package ai.nearby.reunion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalSessionTest {
    @Test fun approachingParkingDoesNotDiscardNewArrival() {
        val session = ArrivalSession()
        session.onArrival(false)
        assertFalse(session.onVehicle(false, "NOT_P", 2.0))
        assertTrue(session.hasActiveArrival)
        assertFalse(session.onVehicle(true, "P", 0.0))
        assertTrue(session.hasActiveArrival)
    }

    @Test fun staleThenDepartureInvalidatesAndCannotBeRevivedByReparking() {
        val session = ArrivalSession()
        session.onArrival(false)
        session.onVehicle(true, "P", 0.0)
        assertFalse(session.onVehicle(false, "UNKNOWN", null))
        assertTrue(session.hasActiveArrival)
        assertTrue(session.onVehicle(false, "NOT_P", 0.0))
        assertFalse(session.hasActiveArrival)
        assertFalse(session.onVehicle(true, "P", 0.0))
        assertFalse(session.hasActiveArrival)
    }

    @Test fun arrivalWhileAlreadyParkedIsInvalidatedOnDeparture() {
        val session = ArrivalSession()
        session.onArrival(true)
        assertTrue(session.onVehicle(false, "NOT_P", 0.0))
        assertFalse(session.hasActiveArrival)
    }

    @Test fun confirmedForwardOrReverseMotionInvalidatesWithoutGear() {
        for (speed in listOf(0.1, -0.1)) {
            val session = ArrivalSession()
            session.onArrival(true)
            assertTrue(session.onVehicle(false, "UNKNOWN", speed))
            assertFalse(session.hasActiveArrival)
        }
    }

    @Test fun unknownOrInvalidSignalsDoNotInventDeparture() {
        val session = ArrivalSession()
        session.onArrival(true)
        assertFalse(session.onVehicle(false, null, Double.NaN))
        assertFalse(session.onVehicle(false, "UNKNOWN", null))
        assertTrue(session.hasActiveArrival)
        session.clear()
        assertFalse(session.hasActiveArrival)
        session.onArrival(false)
        assertTrue(session.hasActiveArrival)
    }

    @Test fun sensorZeroToleranceDoesNotInvalidateArrival() {
        val session = ArrivalSession()
        session.onArrival(true)
        assertFalse(session.onVehicle(true, "P", 0.05f.toDouble()))
        assertFalse(session.onVehicle(false, "P", (-0.05f).toDouble()))
        assertTrue(session.hasActiveArrival)
    }
}
