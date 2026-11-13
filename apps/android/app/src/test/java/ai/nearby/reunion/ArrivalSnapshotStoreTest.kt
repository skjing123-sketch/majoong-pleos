package ai.nearby.reunion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalSnapshotStoreTest {
    @Test fun visibleParkedBrowsingSurvivesNudgeExpiryButCannotReplayAfterHiding() {
        val service = ArrivalSnapshotStore<String>(1_000)
        service.onArrival("open detail", true, 5_000)
        assertFalse(service.expire(6_001, keepVisibleParked = true))
        assertEquals("open detail", service.current)
        assertTrue(service.expire(6_001, keepVisibleParked = false))
        val returningScreen = ArrivalSnapshotStore<String>()
        returningScreen.replaceSnapshot(service.current, true)
        assertNull(returningScreen.current)
    }

    @Test fun expiredServiceArrivalCannotReviveOnRebindWithoutDeparture() {
        val service = ArrivalSnapshotStore<String>(1_000)
        val screen = ArrivalSnapshotStore<String>()
        service.onArrival("parked arrival", true, 5_000)
        assertFalse(service.expire(5_999))
        assertTrue(service.expire(6_000))
        screen.replaceSnapshot(service.current, true)
        assertNull(screen.current)
        assertFalse(service.expire(6_001))
    }

    @Test fun newSdkArrivalHasItsOwnDeadlineAndClockRegressionFailsClosed() {
        val service = ArrivalSnapshotStore<String>(1_000)
        service.onArrival("old", false, 5_000)
        service.onArrival("new", false, 5_900)
        assertFalse(service.expire(6_000))
        assertEquals("new", service.current)
        assertTrue(service.expire(5_899))
        assertNull(service.current)
    }

    @Test fun hiddenDepartureReparkingAndRebindCannotReplayOldArrival() {
        val service = ArrivalSnapshotStore<String>()
        val screen = ArrivalSnapshotStore<String>()
        service.onArrival("first trip", true)
        screen.replaceSnapshot(service.current, true)
        screen.clear() // Activity hides; it no longer receives the service's live changes.
        service.onVehicle(false, "NOT_P", 2.0)
        service.onVehicle(true, "P", 0.0)
        screen.replaceSnapshot(service.current, true)
        assertNull(service.current)
        assertNull(screen.current)
    }

    @Test fun fullEmptySnapshotReplacesEvenAnUnclearedScreenCache() {
        val screen = ArrivalSnapshotStore<String>()
        screen.onArrival("old trip", true)
        screen.replaceSnapshot(null, true)
        assertNull(screen.current)
    }

    @Test fun newArrivalWhileHiddenIsRestoredFromServiceSnapshot() {
        val service = ArrivalSnapshotStore<String>()
        val screen = ArrivalSnapshotStore<String>()
        service.onArrival("first trip", true)
        screen.replaceSnapshot(service.current, true)
        screen.clear()
        service.onVehicle(false, "NOT_P", 1.0)
        service.onArrival("second trip", false)
        service.onVehicle(true, "P", 0.0)
        screen.replaceSnapshot(service.current, true)
        assertEquals("second trip", screen.current)
    }
}
