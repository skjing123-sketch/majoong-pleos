package ai.nearby.reunion

import org.junit.Assert.*
import org.junit.Test

class ConnectionAttemptDeadlineTest {
    @Test fun timeoutPreventsLateConnectedCallback() {
        val state = ConnectionAttemptDeadline()
        val attempt = state.begin()
        assertTrue(state.complete(attempt)) // deadline owns the completion
        assertFalse(state.complete(attempt)) // late SDK success cannot reopen it
    }

    @Test fun newerAttemptRejectsCallbacksFromSupersededAttempt() {
        val state = ConnectionAttemptDeadline()
        val old = state.begin()
        val next = state.begin()
        assertFalse(state.complete(old))
        assertTrue(state.complete(next))
    }

    @Test fun stopAndRetryRejectPreviousCallbacks() {
        val state = ConnectionAttemptDeadline()
        val old = state.begin()
        state.cancel()
        assertFalse(state.complete(old))
        val next = state.begin()
        assertNotEquals(old, next)
        assertFalse(state.complete(old))
        assertTrue(state.complete(next))
    }
}
