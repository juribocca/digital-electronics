package com.namirial.trust.electronics.clock

import com.namirial.trust.electronics.core.Input
import com.namirial.trust.electronics.sequential.TFlipFlop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClockTest {

    @Test fun `ManualClock ticks registered components`() {
        val t = Input(true)
        val ff = TFlipFlop(t)
        val clock = ManualClock()
        clock.register(ff)

        assertFalse(ff.q.value)
        clock.tick()
        assertTrue(ff.q.value)
        clock.tick()
        assertFalse(ff.q.value)
    }

    @Test fun `ManualClock tracks cycle count`() {
        val clock = ManualClock()
        assertEquals(0L, clock.cycle)
        clock.tick()
        clock.tick()
        assertEquals(2L, clock.cycle)
    }

    @Test fun `TimedClock auto-ticks on schedule`() = runTest {
        val t = Input(true)
        val ff = TFlipFlop(t)
        val clock = TimedClock(100.milliseconds)
        clock.register(ff)

        clock.start(this)
        advanceTimeBy(350.milliseconds)
        assertTrue(clock.cycle >= 3)
        clock.stop()
    }

    @Test fun `TimedClock stop halts ticking`() = runTest {
        val clock = TimedClock(100.milliseconds)
        clock.start(this)
        advanceTimeBy(250.milliseconds)
        clock.stop()
        val cycleAtStop = clock.cycle
        advanceTimeBy(500.milliseconds)
        assertEquals(cycleAtStop, clock.cycle)
    }
}
