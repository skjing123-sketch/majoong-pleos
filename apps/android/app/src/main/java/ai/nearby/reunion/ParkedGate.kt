package ai.nearby.reunion

import kotlin.math.abs

/** Pure policy; unknown/stale input never grants permission to display photographs. */
object ParkedGate {
    data class Input(
        val connected: Boolean,
        val gearIsPark: Boolean?,
        val speedMetersPerSecond: Float?,
        val restrictionsKnown: Boolean,
        val requiresDistractionOptimization: Boolean,
        val activeRestrictions: Int,
        val signalsFresh: Boolean,
    )
    data class Decision(val allowed: Boolean, val reason: String)

    fun evaluate(input: Input): Decision = when {
        !input.connected -> Decision(false, "vehicle_disconnected")
        !input.signalsFresh -> Decision(false, "vehicle_state_stale")
        !input.restrictionsKnown -> Decision(false, "ux_restrictions_unknown")
        input.gearIsPark == null || input.speedMetersPerSecond == null -> Decision(false, "vehicle_state_unknown")
        !input.speedMetersPerSecond.isFinite() -> Decision(false, "invalid_speed")
        input.gearIsPark != true -> Decision(false, "gear_not_park")
        abs(input.speedMetersPerSecond) > 0.05f -> Decision(false, "vehicle_moving")
        input.requiresDistractionOptimization || input.activeRestrictions != 0 -> Decision(false, "ux_restricted")
        else -> Decision(true, "gear_p_speed_zero_ux_unrestricted")
    }
}
