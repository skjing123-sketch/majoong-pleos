package ai.nearby.reunion

/** Test-only genuine SDK read telemetry. Independent of arrival proof's budget; never supplies vehicle data. */
internal class VehicleSampleRecorder(private val elapsedMillis: () -> Long, private val write: (String) -> Unit) {
    private var startedAt = -1L
    private var lastEmissionAt = -1L
    private var lastReadAt = -1L
    private var invalidWritten = false
    private var samples = 0

    @Synchronized fun start() {
        startedAt = elapsedMillis()
        lastEmissionAt = -1L; lastReadAt = -1L; invalidWritten = false; samples = 0
    }
    @Synchronized fun expireIfDue(sequence: Long) {
        val now = elapsedMillis()
        if (startedAt >= 0 && (now < startedAt || now - startedAt >= 300_000L)) end(sequence, now, true, false)
    }
    @Synchronized fun stop(sequence: Long) {
        if (startedAt >= 0) end(sequence, elapsedMillis(), false, true)
    }
    @Synchronized fun acceptedRead(sequence: Long, readElapsedMs: Long, speedMps: Float?, parked: Boolean?) {
        val now = elapsedMillis()
        expireIfDue(sequence)
        if (startedAt < 0) return
        if (readElapsedMs <= 0 || now < readElapsedMs || now - readElapsedMs > 3_000L ||
            speedMps == null || !speedMps.isFinite() || parked == null) {
            unavailable(sequence); return
        }
        // Same speed from a new paired SDK read IS a heartbeat; the same old read is not.
        if (readElapsedMs <= lastReadAt || !canEmit(now)) return
        val rounded = kotlin.math.round(speedMps.toDouble() * 1000.0) / 1000.0
        val finiteSpeed = if (rounded == 0.0) 0.0 else rounded
        emit("event=vehicle_sample seq=${sequence.coerceAtLeast(0)} elapsedMs=$readElapsedMs speedMps=$finiteSpeed parked=$parked fresh=true")
        lastReadAt = readElapsedMs; lastEmissionAt = now; invalidWritten = false; samples++
    }
    @Synchronized fun unavailable(sequence: Long) {
        val now = elapsedMillis()
        expireIfDue(sequence)
        if (startedAt < 0 || invalidWritten || !canEmit(now)) return
        emit("event=vehicle_sample seq=${sequence.coerceAtLeast(0)} elapsedMs=$now speedMps=unknown parked=false fresh=false")
        lastEmissionAt = now; invalidWritten = true; samples++
    }
    private fun canEmit(now: Long) = samples < 300 && (lastEmissionAt < 0 || now - lastEmissionAt >= 1000L)
    private fun end(sequence: Long, now: Long, expired: Boolean, stopped: Boolean) {
        startedAt = -1L
        emit("event=vehicle_sample_end seq=${sequence.coerceAtLeast(0)} elapsedMs=${now.coerceAtLeast(0)} expired=$expired stopped=$stopped fresh=false")
    }
    private fun emit(message: String) { runCatching { write(message) } }
}
