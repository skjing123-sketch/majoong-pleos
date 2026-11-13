package ai.nearby.reunion

import kotlin.math.abs

/** Keeps an arrival pending before parking, then invalidates it on a confirmed departure. */
class ArrivalSession {
    var hasActiveArrival: Boolean = false
        private set
    private var parkedSinceArrival = false

    fun onArrival(displayAllowed: Boolean) {
        hasActiveArrival = true
        parkedSinceArrival = displayAllowed
    }

    /** Returns true when the caller must remove its cached arrival payload. */
    fun onVehicle(displayAllowed: Boolean, gear: String?, speedMetersPerSecond: Double?): Boolean {
        if (!hasActiveArrival) return false
        val moving = gear == "NOT_P" ||
            (speedMetersPerSecond != null && speedMetersPerSecond.isFinite() && abs(speedMetersPerSecond) > 0.05f.toDouble())
        if (parkedSinceArrival && !displayAllowed && moving) {
            clear()
            return true
        }
        if (displayAllowed) parkedSinceArrival = true
        return false
    }

    fun clear() {
        hasActiveArrival = false
        parkedSinceArrival = false
    }
}
