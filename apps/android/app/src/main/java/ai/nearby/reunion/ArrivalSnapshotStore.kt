package ai.nearby.reunion

/** A complete snapshot replaces absence as well as presence; detached screens cannot keep old arrivals. */
class ArrivalSnapshotStore<T>(private val ttlMillis: Long = 5 * 60_000L) {
    private val session = ArrivalSession()
    private var arrivedAt: Long? = null
    var current: T? = null
        private set

    fun onArrival(value: T, displayAllowed: Boolean, elapsedRealtime: Long? = null) {
        current = value
        arrivedAt = elapsedRealtime
        session.onArrival(displayAllowed)
    }

    /** Service uses monotonic time; a detached screen cannot renew this expiry by rebinding. */
    fun expire(elapsedRealtime: Long, keepVisibleParked: Boolean = false): Boolean {
        val received = arrivedAt ?: return false
        // The five-minute limit is a nudge/re-entry limit, not a timer closing an active detail view.
        if (keepVisibleParked && elapsedRealtime >= received) return false
        if (current == null || (elapsedRealtime >= received && elapsedRealtime - received < ttlMillis)) return false
        clear()
        return true
    }

    fun onVehicle(displayAllowed: Boolean, gear: String?, speed: Double?): Boolean {
        val removed = session.onVehicle(displayAllowed, gear, speed)
        if (removed) { current = null; arrivedAt = null }
        return removed
    }

    fun replaceSnapshot(value: T?, displayAllowed: Boolean) {
        clear()
        if (value != null) onArrival(value, displayAllowed)
    }

    fun clear() {
        current = null
        arrivedAt = null
        session.clear()
    }
}
