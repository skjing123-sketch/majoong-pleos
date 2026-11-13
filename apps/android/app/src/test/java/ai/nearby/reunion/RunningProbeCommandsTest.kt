package ai.nearby.reunion

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningProbeCommandsTest {
    @Test fun requestBeforeServiceStartIsRejectedAndNeverReplayedLater() {
        val commands = RunningProbeCommands { true }
        val received = mutableListOf<String>()
        assertEquals(RunningProbeCommands.Result.UNAVAILABLE, commands.dispatch("route_sample"))
        commands.attach(Any(), received::add)
        assertEquals(emptyList<String>(), received)
        assertEquals(RunningProbeCommands.Result.ACCEPTED, commands.dispatch("observe"))
        assertEquals(listOf("observe"), received)
    }

    @Test fun stoppedTripRejectsCommandsWithoutRestartingIt() {
        val commands = RunningProbeCommands { true }
        val session = Any()
        val received = mutableListOf<String>()
        commands.attach(session, received::add)
        assertEquals(RunningProbeCommands.Result.ACCEPTED, commands.dispatch("route_sample"))
        commands.detach(session)
        assertEquals(RunningProbeCommands.Result.UNAVAILABLE, commands.dispatch("observe"))
        assertEquals(listOf("route_sample"), received)
    }

    @Test fun staleServiceCleanupCannotDetachTheNewRunningSession() {
        val commands = RunningProbeCommands { true }
        val oldSession = Any()
        val newSession = Any()
        val oldCalls = mutableListOf<String>()
        val newCalls = mutableListOf<String>()
        commands.attach(oldSession, oldCalls::add)
        commands.attach(newSession, newCalls::add)
        commands.detach(oldSession)
        assertEquals(RunningProbeCommands.Result.ACCEPTED, commands.dispatch("observe"))
        assertEquals(emptyList<String>(), oldCalls)
        assertEquals(listOf("observe"), newCalls)
    }

    @Test fun arbitraryCommandsCannotInjectArrivalsOrChangeDestinations() {
        val commands = RunningProbeCommands { true }
        val received = mutableListOf<String>()
        commands.attach(Any(), received::add)
        for (command in listOf(null, "", "arrival", "position-destination", "route_sample 1,2", "connect")) {
            assertEquals(RunningProbeCommands.Result.UNSUPPORTED, commands.dispatch(command))
        }
        assertEquals(emptyList<String>(), received)
    }

    @Test fun disabledBuildNeverAttachesOrExecutesACommand() {
        val commands = RunningProbeCommands { false }
        val received = mutableListOf<String>()
        commands.attach(Any(), received::add)
        assertEquals(RunningProbeCommands.Result.DISABLED, commands.dispatch("observe"))
        assertEquals(RunningProbeCommands.Result.DISABLED, commands.dispatch("route_sample"))
        assertEquals(emptyList<String>(), received)
    }
}
