package ai.nearby.reunion

/** Dispatches a fixed diagnostic command only to an already-running monitoring session. */
class RunningProbeCommands(private val enabled: () -> Boolean) {
    enum class Result(val code: Int, val label: String) {
        ACCEPTED(1, "accepted"),
        DISABLED(-1, "debug_disabled"),
        UNSUPPORTED(-2, "unsupported_command"),
        UNAVAILABLE(-3, "service_not_running"),
    }

    private var owner: Any? = null
    private var execute: ((String) -> Unit)? = null

    // All calls are on the application's main thread. No queue or service-start fallback exists.
    fun attach(session: Any, handler: (String) -> Unit) {
        if (!enabled()) return
        owner = session
        execute = handler
    }

    fun detach(session: Any) {
        if (owner !== session) return
        owner = null
        execute = null
    }

    fun dispatch(command: String?): Result {
        if (!enabled()) return Result.DISABLED
        if (command != "observe" && command != "route_sample") return Result.UNSUPPORTED
        val handler = execute ?: return Result.UNAVAILABLE
        handler(command)
        return Result.ACCEPTED
    }
}
