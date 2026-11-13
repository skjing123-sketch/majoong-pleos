package ai.nearby.reunion

/** Checks the captured connection generation at delivery time, including callbacks already queued. */
class SessionCallbackScope(
    private val isCurrent: () -> Boolean,
    private val enqueue: (() -> Unit) -> Unit,
) {
    fun dispatch(callback: () -> Unit) {
        enqueue { if (isCurrent()) callback() }
    }
}
