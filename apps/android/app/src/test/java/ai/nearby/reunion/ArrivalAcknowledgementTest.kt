package ai.nearby.reunion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalAcknowledgementTest {
    @Test fun bridgeReceiptSuppressesUnbindToastBeforeQueuedUiWorkRuns() {
        val receipt = ArrivalAcknowledgement()
        val notice = ArrivalNoticePolicy()
        val queuedUiWork = mutableListOf<() -> Unit>()
        receipt.onArrival(9)
        notice.onArrival(9, 1_000)

        // WebView bridge thread records immediately; main-thread reconciliation is still queued.
        val bridge = Thread {
            assertTrue(receipt.markHandled(9))
            queuedUiWork.add { notice.markForegroundHandled(receipt.handledSequence()) }
        }
        bridge.start()
        bridge.join()
        assertEquals(1, queuedUiWork.size)
        // onPause cleared the screen and onUnbind runs ahead of queued reconciliation.
        notice.markForegroundHandled(receipt.handledSequence())
        assertNull(notice.takeToast(1_100, displayAllowed = true, uiVisible = false))
        queuedUiWork.forEach { it() }
        assertNull(notice.takeToast(1_200, true, false))
    }

    @Test fun staleOrInvalidReceiptCannotConsumeTheNextArrival() {
        val receipt = ArrivalAcknowledgement()
        receipt.onArrival(1)
        assertTrue(receipt.markHandled(1))
        receipt.onArrival(2)
        assertFalse(receipt.markHandled(1))
        assertFalse(receipt.markHandled(0))
        assertEquals(0L, receipt.handledSequence())
        assertTrue(receipt.markHandled(2))
        assertEquals(2L, receipt.handledSequence())
    }

    @Test fun clearOnStopRejectsLateReceiptAndNewServiceStartsUnconsumed() {
        val receipt = ArrivalAcknowledgement()
        receipt.onArrival(1)
        receipt.clear()
        assertFalse(receipt.markHandled(1))
        val newService = ArrivalAcknowledgement()
        newService.onArrival(1)
        assertEquals(0L, newService.handledSequence())
    }
}
