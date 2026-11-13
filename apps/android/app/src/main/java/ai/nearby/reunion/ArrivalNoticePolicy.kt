package ai.nearby.reunion

/** Pure policy for a one-shot background invitation; it never creates an arrival or authorizes parking. */
class ArrivalNoticePolicy(private val ttlMillis: Long = 5 * 60_000L) {
    private var sequence = 0L
    private var arrivedAt = 0L
    private var toastAttempted = false
    private var foregroundHandled = false

    fun onArrival(sequence: Long, elapsedRealtime: Long) {
        if (sequence <= 0 || sequence == this.sequence) return
        this.sequence = sequence
        arrivedAt = elapsedRealtime
        toastAttempted = false
        foregroundHandled = false
    }

    fun isFresh(elapsedRealtime: Long): Boolean = sequence > 0 &&
        elapsedRealtime >= arrivedAt && elapsedRealtime - arrivedAt < ttlMillis

    fun takeToast(elapsedRealtime: Long, displayAllowed: Boolean, uiVisible: Boolean): Long? {
        if (!isFresh(elapsedRealtime) || !displayAllowed || uiVisible || toastAttempted || foregroundHandled) return null
        toastAttempted = true
        return sequence
    }

    fun markForegroundHandled(sequence: Long) {
        if (sequence > 0 && this.sequence == sequence) foregroundHandled = true
    }

    fun clear() {
        sequence = 0L
        arrivedAt = 0L
        toastAttempted = false
        foregroundHandled = false
    }
}
