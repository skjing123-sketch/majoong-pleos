package ai.nearby.reunion

import java.util.WeakHashMap

/** Used only by IPC workers. Serializes a manager's single-listener registration and cleanup. */
class ConnectionRegistration {
    private var owner: Any? = null

    @Synchronized fun register(owner: Any, isCurrent: () -> Boolean, action: () -> Unit): Boolean {
        if (!isCurrent()) return false
        this.owner = owner
        action()
        return true
    }

    @Synchronized fun remove(owner: Any, action: () -> Unit): Boolean {
        if (this.owner !== owner) return false
        try { action() } finally { this.owner = null }
        return true
    }
}

internal object VehicleUxRegistrations {
    private val registrations = WeakHashMap<Any, ConnectionRegistration>()

    @Synchronized fun forManager(manager: Any): ConnectionRegistration =
        registrations.getOrPut(manager) { ConnectionRegistration() }
}
