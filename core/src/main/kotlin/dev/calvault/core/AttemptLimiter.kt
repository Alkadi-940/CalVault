package dev.calvault.core

import java.io.File
import kotlin.math.min

/** Delays use monotonic time. A reboot restarts any outstanding delay instead of trusting wall time. */
class AttemptLimiter(private val file: File, private val bootId: () -> Long, private val ticks: () -> Long) {
    private data class State(val failures: Int, val boot: Long, val until: Long, val duration: Long)
    private fun load(): State {
        if (!file.exists()) return State(0, bootId(), 0, 0)
        return try {
            decode(file.readBounded(64)) {
                require(readInt() == 1)
                State(readInt().coerceIn(0, 10000), readLong(), readLong(), readLong().coerceIn(0, 900000))
            }
        } catch (_: Exception) {
            // Fail closed for a corrupt limiter record, without destroying vault data.
            State(5, bootId(), ticks() + 30000, 30000).also(::save)
        }
    }
    private fun save(state: State) = atomicWrite(file, encode {
        writeInt(1); writeInt(state.failures); writeLong(state.boot); writeLong(state.until); writeLong(state.duration)
    })
    @Synchronized fun remainingMillis(): Long {
        var state = load()
        if (state.boot != bootId() && state.duration > 0) {
            state = state.copy(boot = bootId(), until = ticks() + state.duration)
            save(state)
        }
        return (state.until - ticks()).coerceAtLeast(0)
    }
    @Synchronized fun failure() {
        val previous = load()
        val count = (previous.failures + 1).coerceAtMost(10000)
        val duration = if (count < 5) 0 else min(900000L, 30000L * (1L shl min(count - 5, 5)))
        save(State(count, bootId(), ticks() + duration, duration))
    }
    @Synchronized fun success() = save(State(0, bootId(), 0, 0))
}
