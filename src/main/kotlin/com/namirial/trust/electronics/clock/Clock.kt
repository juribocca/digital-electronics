package com.namirial.trust.electronics.clock

import com.namirial.trust.electronics.sequential.Clockable
import kotlinx.coroutines.*
import kotlin.time.Duration

/**
 * Manual clock. Call [tick] to advance one cycle.
 * All registered [Clockable] components are triggered in order.
 */
class ManualClock {
    private val components = mutableListOf<Clockable>()
    var cycle: Long = 0L
        private set

    fun register(vararg clockables: Clockable) {
        components.addAll(clockables)
    }

    fun tick() {
        components.forEach { it.clock() }
        cycle++
    }
}

/**
 * Timed clock. Runs in a coroutine, auto-ticking at the given [period].
 * Call [start] to begin and [stop] to halt. Uses a [ManualClock] internally.
 */
class TimedClock(private val period: Duration) {
    val manualClock = ManualClock()
    private var job: Job? = null

    fun register(vararg clockables: Clockable) {
        manualClock.register(*clockables)
    }

    val cycle: Long get() = manualClock.cycle

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                manualClock.tick()
                delay(period)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
