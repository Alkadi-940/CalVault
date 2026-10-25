package dev.calvault.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttemptLimiterTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun attemptsPersistAndRebootDoesNotBypassDelay() {
        var boot = 1L
        var elapsed = 1000L
        val file = temp.root.resolve("attempts")
        var limiter = AttemptLimiter(file, { boot }, { elapsed })
        repeat(4) { limiter.failure(); assertEquals(0, limiter.remainingMillis()) }
        limiter.failure(); assertEquals(30000, limiter.remainingMillis())
        elapsed += 5000
        limiter = AttemptLimiter(file, { boot }, { elapsed })
        assertEquals(25000, limiter.remainingMillis())
        boot++; elapsed = 1
        assertEquals(30000, limiter.remainingMillis())
        elapsed += 30000; assertEquals(0, limiter.remainingMillis())
        limiter.failure(); assertEquals(60000, limiter.remainingMillis())
        limiter.success(); assertEquals(0, limiter.remainingMillis())
    }
}
