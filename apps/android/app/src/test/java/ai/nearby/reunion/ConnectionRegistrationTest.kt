package ai.nearby.reunion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRegistrationTest {
    @Test fun oldSetupCleanupCannotRemoveNewListenerOnReusedManager() {
        val registration = ConnectionRegistration()
        val old = Any()
        val current = Any()
        var installed = ""
        assertTrue(registration.register(old, { true }) { installed = "old" })
        assertTrue(registration.register(current, { true }) { installed = "current" })
        assertFalse(registration.remove(old) { installed = "" })
        assertEquals("current", installed)
        assertTrue(registration.remove(current) { installed = "" })
        assertEquals("", installed)
    }

    @Test fun staleSetupCannotClaimOrReplaceCurrentRegistration() {
        val registration = ConnectionRegistration()
        val current = Any()
        var installed = "current"
        registration.register(current, { true }) {}
        assertFalse(registration.register(Any(), { false }) { installed = "stale" })
        assertEquals("current", installed)
        assertTrue(registration.remove(current) { installed = "" })
    }

    @Test fun failedRegistrationStillHasAnOwnerForItsPartialCleanup() {
        val registration = ConnectionRegistration()
        val owner = Any()
        runCatching { registration.register(owner, { true }) { error("registration failed") } }
        assertTrue(registration.remove(owner) {})
        assertFalse(registration.remove(owner) { error("duplicate cleanup") })
    }
}
