package ai.nearby.reunion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleReadWindowTest {
    @Test fun queuedSuccessCannotBecomeFreshWhenMainThreadResumesAfterDeadline() {
        val window = VehicleReadWindow(startedAt = 1_000)
        assertTrue(window.accepts(5_000)) // IPC completed and queued its result at t+4s.
        assertFalse(window.accepts(9_000)) // Main resumes t+8s; timer callback has not run yet.
    }

    @Test fun exactDeadlineAndClockRegressionFailClosed() {
        val window = VehicleReadWindow(startedAt = 1_000)
        assertTrue(window.accepts(5_999))
        assertFalse(window.accepts(6_000))
        assertFalse(window.accepts(999))
    }
}
