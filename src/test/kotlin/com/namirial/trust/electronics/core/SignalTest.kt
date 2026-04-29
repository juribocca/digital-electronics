package com.namirial.trust.electronics.core

import kotlin.test.*

class SignalTest {

    @Test fun `Input defaults to false`() = assertFalse(Input().value)

    @Test fun `Input can be initialized to true`() = assertTrue(Input(true).value)

    @Test fun `Input value is mutable`() {
        val i = Input()
        i.value = true
        assertTrue(i.value)
    }

    @Test fun `Output evaluates lazily`() {
        val i = Input()
        val o = Output { !i.value }
        assertTrue(o.value)
        i.value = true
        assertFalse(o.value)
    }

    @Test fun `LatchSignal defaults to false`() = assertFalse(LatchSignal().value)

    @Test fun `LatchSignal holds assigned value`() {
        val l = LatchSignal()
        l.value = true
        assertTrue(l.value)
        l.value = false
        assertFalse(l.value)
    }
}
