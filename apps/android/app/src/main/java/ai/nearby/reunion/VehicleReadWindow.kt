package ai.nearby.reunion

/** A queued callback cannot renew a vehicle sample by stamping its eventual delivery time. */
data class VehicleReadWindow(val startedAt: Long, private val timeoutMillis: Long = 5_000L) {
    fun accepts(appliedAt: Long): Boolean = appliedAt >= startedAt && appliedAt - startedAt < timeoutMillis
}
