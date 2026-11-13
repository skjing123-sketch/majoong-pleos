package ai.nearby.reunion

/** Synchronous bridge-to-service receipt; it must not depend on the Activity's main-thread queue. */
class ArrivalAcknowledgement {
    private var currentSequence = 0L
    private var handled = false

    @Synchronized fun onArrival(sequence: Long) {
        if (sequence <= 0 || sequence == currentSequence) return
        currentSequence = sequence
        handled = false
    }

    @Synchronized fun markHandled(sequence: Long): Boolean {
        if (sequence <= 0 || sequence != currentSequence) return false
        handled = true
        return true
    }

    @Synchronized fun handledSequence(): Long = if (handled) currentSequence else 0L

    @Synchronized fun clear() {
        currentSequence = 0L
        handled = false
    }
}
