package ai.nearby.reunion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalNoticePolicyTest {
    @Test fun requiresArrivalFreshParkingAndHiddenUi() {
        val policy = ArrivalNoticePolicy()
        assertNull(policy.takeToast(1_000, true, false))
        policy.onArrival(1, 1_000)
        assertNull(policy.takeToast(1_100, false, false))
        assertNull(policy.takeToast(1_200, true, true))
        assertEquals(1L, policy.takeToast(1_300, true, false))
    }

    @Test fun repeatedVehicleCallbacksOrSameArrivalDoNotRepeatToast() {
        val policy = ArrivalNoticePolicy()
        policy.onArrival(7, 1_000)
        assertEquals(7L, policy.takeToast(1_000, true, false))
        policy.onArrival(7, 2_000)
        repeat(100) { assertNull(policy.takeToast(2_000L + it, true, false)) }
        policy.onArrival(8, 3_000)
        assertEquals(8L, policy.takeToast(3_100, true, false))
    }

    @Test fun fiveMinuteExpiryCannotBeExtendedByRepeatedArrivalOrParking() {
        val policy = ArrivalNoticePolicy()
        policy.onArrival(1, 1_000)
        assertTrue(policy.isFresh(300_999))
        assertFalse(policy.isFresh(301_000))
        policy.onArrival(1, 300_000)
        assertFalse(policy.isFresh(301_001))
        assertNull(policy.takeToast(301_001, true, false))
    }

    @Test fun nonMonotonicTimeAndInvalidSequenceFailClosed() {
        val policy = ArrivalNoticePolicy()
        policy.onArrival(0, 1_000)
        assertNull(policy.takeToast(1_000, true, false))
        policy.onArrival(1, 1_000)
        assertNull(policy.takeToast(999, true, false))
    }

    @Test fun alreadyHandledForegroundBannerSuppressesLaterNativeToast() {
        val policy = ArrivalNoticePolicy()
        policy.onArrival(2, 1_000)
        policy.markForegroundHandled(1)
        assertNull(policy.takeToast(1_100, true, true))
        policy.markForegroundHandled(2)
        assertNull(policy.takeToast(1_200, true, false))
    }

    @Test fun departureOrStopClearPendingNoticeAndNextTripCanNotify() {
        val policy = ArrivalNoticePolicy()
        policy.onArrival(1, 1_000)
        policy.clear()
        assertNull(policy.takeToast(1_100, true, false))
        policy.onArrival(2, 2_000)
        assertEquals(2L, policy.takeToast(2_100, true, false))
    }

    @Test fun unsafeGateDoesNotConsumePendingArrivalBeforeParking() {
        val policy = ArrivalNoticePolicy()
        policy.onArrival(1, 1_000)
        repeat(5) { assertNull(policy.takeToast(1_000L + it, false, false)) }
        assertEquals(1L, policy.takeToast(2_000, true, false))
    }
}
