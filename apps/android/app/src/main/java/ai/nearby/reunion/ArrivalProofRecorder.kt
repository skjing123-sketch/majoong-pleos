package ai.nearby.reunion

/** Typed, bounded release evidence. No addresses, coordinates, URLs, names or contact data enter this API. */
internal class ArrivalProofRecorder(
    private val elapsedMillis: () -> Long,
    private val write: (String) -> Unit,
) {
    private var windowStart = -1L
    private var records = 0
    private var sdkWindowStart = -1L
    private var sdkRecords = 0
    private var lastSdk = 0L
    private var lastGate: String? = null
    private var lastUi: String? = null
    private var lastActivity: String? = null

    @Synchronized fun sdkArrival(sequence: Long) {
        if (sequence <= 0 || sequence <= lastSdk) return
        if (emit("event=sdk_arrival seq=$sequence", sdkCallback = true)) lastSdk = sequence
    }

    @Synchronized fun gate(sequence: Long, parked: Boolean?, zeroSpeed: Boolean?, uxAllowed: Boolean?, fresh: Boolean, allowed: Boolean) {
        if (sequence <= 0) return
        val record = "event=vehicle_gate seq=$sequence parkedKnown=${parked != null} parked=${parked == true} speedKnown=${zeroSpeed != null} zeroSpeed=${zeroSpeed == true} uxKnown=${uxAllowed != null} uxAllowed=${uxAllowed == true} fresh=$fresh allowed=$allowed"
        if (record != lastGate && emit(record)) lastGate = record
    }

    @Synchronized fun ui(sequence: Long, visible: Boolean, gateUncovered: Boolean, allowed: Boolean, live: Boolean, reportedPhotoDecoded: Boolean, reportedNudgeVisible: Boolean) {
        if (sequence <= 0) return
        val nudgeVisible = visible && gateUncovered && allowed && live && reportedNudgeVisible
        val photoDecoded = nudgeVisible && reportedPhotoDecoded
        val record = "event=ui seq=$sequence visible=$visible gateUncovered=$gateUncovered allowed=$allowed live=$live photoDecoded=$photoDecoded nudgeVisible=$nudgeVisible"
        if (record != lastUi && emit(record)) lastUi = record
    }

    @Synchronized fun activity(sequence: Long, resumed: Boolean, visible: Boolean, multiWindow: Boolean, stopped: Boolean) {
        val record = "event=activity seq=${sequence.coerceAtLeast(0)} resumed=$resumed visible=$visible multiWindow=$multiWindow stopped=$stopped"
        if (record != lastActivity && emit(record)) lastActivity = record
    }

    private fun emit(record: String, sdkCallback: Boolean = false): Boolean {
        val now = elapsedMillis().coerceAtLeast(0)
        if (sdkCallback) {
            if (sdkWindowStart < 0 || now < sdkWindowStart || now - sdkWindowStart >= 60_000L) {
                sdkWindowStart = now; sdkRecords = 0
            }
            if (sdkRecords >= 60) return false
            sdkRecords++
        } else {
            if (windowStart < 0 || now < windowStart || now - windowStart >= 60_000L) {
                windowStart = now; records = 0
            }
            if (records >= 60) return false
            records++
        }
        // Evidence collection must never interrupt the application's SDK callback or UI.
        return runCatching { write("$record elapsedMs=$now") }.isSuccess
    }
}
