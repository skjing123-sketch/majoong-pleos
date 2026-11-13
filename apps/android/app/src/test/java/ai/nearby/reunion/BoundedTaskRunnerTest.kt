package ai.nearby.reunion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BoundedTaskRunnerTest {
    @Test fun blockedOldConnectionAllowsOneRecoveryButNeverQueuesMoreWork() {
        val runner = BoundedTaskRunner(2)
        val firstStarted = CountDownLatch(1)
        val recoveryStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val rejectedRan = AtomicBoolean(false)
        try {
            assertTrue(runner.tryExecute { firstStarted.countDown(); release.await(); completed.countDown() })
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            assertTrue(runner.tryExecute { recoveryStarted.countDown(); release.await(); completed.countDown() })
            assertTrue(recoveryStarted.await(2, TimeUnit.SECONDS))
            assertFalse(runner.tryExecute { rejectedRan.set(true) })
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            // Rejected work cannot execute later when a worker becomes free.
            runner.close()
            assertFalse(rejectedRan.get())
            assertFalse(runner.tryExecute { rejectedRan.set(true) })
        } finally {
            release.countDown()
            runner.close()
        }
    }
}
