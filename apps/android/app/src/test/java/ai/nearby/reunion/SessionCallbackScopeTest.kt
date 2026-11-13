package ai.nearby.reunion

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCallbackScopeTest {
    @Test fun oldVehicleCallbacksCannotOverwriteReplacementCarConnection() {
        val queue = ArrayDeque<() -> Unit>()
        var active = true
        val generation = 1
        var carConnection = 1L
        var gear = "UNKNOWN"
        var uxRestricted = true
        val oldConnection = carConnection
        val old = SessionCallbackScope({ active && generation == 1 && carConnection == oldConnection }, queue::addLast)
        old.dispatch { gear = "P"; uxRestricted = false }
        carConnection++
        val currentConnection = carConnection
        val current = SessionCallbackScope({ active && generation == 1 && carConnection == currentConnection }, queue::addLast)
        current.dispatch { gear = "NOT_P"; uxRestricted = true }
        old.dispatch { gear = "P"; uxRestricted = false }
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals("NOT_P", gear)
        assertEquals(true, uxRestricted)
        active = false
        current.dispatch { gear = "P" }
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals("NOT_P", gear)
    }

    @Test fun expiredReadResultCannotReopenGateAfterTimeout() {
        val queue = ArrayDeque<() -> Unit>()
        var read = 1L
        val requestedRead = read
        var allowed = false
        val result = SessionCallbackScope({ read == requestedRead }, queue::addLast)
        result.dispatch { allowed = true }
        read++ // timeout invalidates this read even when the connection did not change.
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(false, allowed)
    }

    @Test fun previousHelperQueuedAndLateArrivalsCannotAdvanceNewTripSequence() {
        val queue = ArrayDeque<() -> Unit>()
        var active = true
        var generation = 1
        var arrivalSequence = 0L
        val oldEpoch = generation
        val old = SessionCallbackScope({ active && generation == oldEpoch }, queue::addLast)
        old.dispatch { arrivalSequence++ } // Arrived on Binder thread, not yet delivered on main.
        active = false
        generation++ // stop invalidates the previous helper.
        active = true
        generation++ // start creates a new helper and captures its own epoch.
        val newEpoch = generation
        val current = SessionCallbackScope({ active && generation == newEpoch }, queue::addLast)
        old.dispatch { arrivalSequence++ } // Old helper fires late after restart.
        current.dispatch { arrivalSequence++ }
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(1L, arrivalSequence)
    }

    @Test fun stopRejectsDestinationAndErrorUpdatesAlreadyQueued() {
        val queue = ArrayDeque<() -> Unit>()
        var active = true
        var destination = "unchanged"
        var errors = 0
        val scope = SessionCallbackScope({ active }, queue::addLast)
        scope.dispatch { destination = "old destination" }
        scope.dispatch { errors++ }
        active = false
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals("unchanged", destination)
        assertEquals(0, errors)
    }
}
