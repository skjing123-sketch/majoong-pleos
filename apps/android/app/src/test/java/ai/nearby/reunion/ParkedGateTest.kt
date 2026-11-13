package ai.nearby.reunion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedGateTest {
    private val parked = ParkedGate.Input(true, true, 0f, true, false, 0, true)
    @Test fun allowsOnlyConfirmedUnrestrictedParking() {
        assertTrue(ParkedGate.evaluate(parked).allowed)
        assertFalse(ParkedGate.evaluate(parked.copy(gearIsPark = false)).allowed)
        assertFalse(ParkedGate.evaluate(parked.copy(speedMetersPerSecond = -1f)).allowed)
        assertFalse(ParkedGate.evaluate(parked.copy(activeRestrictions = 16)).allowed)
        assertFalse(ParkedGate.evaluate(parked.copy(requiresDistractionOptimization = true)).allowed)
    }
    @Test fun missingOrStaleOrInvalidSignalsFailClosed() {
        listOf(parked.copy(connected = false), parked.copy(gearIsPark = null),
            parked.copy(speedMetersPerSecond = null), parked.copy(speedMetersPerSecond = Float.NaN),
            parked.copy(restrictionsKnown = false), parked.copy(signalsFresh = false)
        ).forEach { assertFalse(ParkedGate.evaluate(it).allowed) }
    }
}
