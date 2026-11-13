package ai.nearby.reunion

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A blocked Binder call is not cancellable. Bound both threads and queued work instead. */
class BoundedTaskRunner(maxWorkers: Int = 2) : AutoCloseable {
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(0, maxWorkers, 30, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), { task ->
            Thread(task, "majoong-car-${sequence.incrementAndGet()}").apply { isDaemon = true }
        })

    fun tryExecute(task: () -> Unit): Boolean = try {
        executor.execute { task() }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    override fun close() { executor.shutdownNow() }
}

/** Process-wide cap: recreating a Service cannot leave a new pair of blocked threads behind. */
internal object VehicleIpcTasks {
    val runner = BoundedTaskRunner(2)
}
