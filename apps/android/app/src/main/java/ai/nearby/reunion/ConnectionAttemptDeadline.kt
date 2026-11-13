package ai.nearby.reunion

/** One completion wins. Timeout/stop prevents a late SDK callback from reopening that attempt. */
internal class ConnectionAttemptDeadline {
    private var serial = 0L
    private var pending: Long? = null

    fun begin(): Long = (++serial).also { pending = it }

    fun complete(attempt: Long): Boolean {
        if (pending != attempt) return false
        pending = null
        return true
    }

    fun cancel() { pending = null }
}
