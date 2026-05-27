package com.namirial.trust.electronics.amiga500

/**
 * Amiga Keyboard Controller and Input Ports.
 *
 * Keyboard: CIA-A serial port (SDR register) receives keycodes.
 * The keyboard sends 8-bit keycodes inverted and rotated left by 1 bit.
 * CIA-A SP interrupt (FLAG) signals key received.
 *
 * Joystick/Mouse ports:
 * - Port 1 (mouse): JOY0DAT ($DFF00A), POTGOR ($DFF016)
 * - Port 2 (joystick): JOY1DAT ($DFF00C), CIA-A PRA bits 6-7 (fire buttons)
 *
 * JOYxDAT format: Y counter[15:8], X counter[7:0] (quadrature for mouse)
 * For joystick: bit 1 = right, bit 9 = left (XOR with bit above for direction)
 */
class InputController {

    // --- Keyboard ---
    val keyBuffer = ArrayDeque<Int>()
    var keyboardHandshake = false

    /** Queue a key press (raw Amiga keycode, 0–127). Bit 7 = 0 for press, 1 for release. */
    fun keyPress(keycode: Int) { keyBuffer.addLast(keycode and 0x7F) }
    fun keyRelease(keycode: Int) { keyBuffer.addLast((keycode and 0x7F) or 0x80) }

    /**
     * Called on CIA tick — if a key is pending and handshake is complete,
     * load the keycode into CIA-A SDR and trigger SP interrupt.
     */
    fun tick(ciaA: CIA8520) {
        if (keyBuffer.isEmpty()) return
        if (keyboardHandshake) { keyboardHandshake = false; return }
        val code = keyBuffer.removeFirst()
        // Amiga keyboard protocol: invert and rotate left by 1
        val encoded = (code.inv() and 0xFF).let { ((it shl 1) or (it ushr 7)) and 0xFF }
        ciaA.sdr = encoded
        ciaA.icrData = ciaA.icrData or 0x08 // SP interrupt flag
    }

    /** Acknowledge key (handshake pulse on CIA-A). */
    fun acknowledge() { keyboardHandshake = true }

    // --- Joystick / Mouse ports ---
    var joy0dat: Int = 0  // Mouse/joystick port 1 (Y[15:8], X[7:0])
    var joy1dat: Int = 0  // Joystick port 2

    // Fire buttons: CIA-A PRA bit 6 = port 1 fire, bit 7 = port 2 fire (active low)
    var fireButton1: Boolean = false
    var fireButton2: Boolean = false

    /** Set joystick state for port 2 (up/down/left/right/fire). */
    fun setJoystick(up: Boolean, down: Boolean, left: Boolean, right: Boolean, fire: Boolean) {
        // JOY1DAT encoding for joystick:
        // bit 1 = right, bit 0 = right XOR down
        // bit 9 = left, bit 8 = left XOR up
        var dat = 0
        if (right) dat = dat or 0x02
        if (right xor down) dat = dat or 0x01
        if (left) dat = dat or 0x200
        if (left xor up) dat = dat or 0x100
        joy1dat = dat
        fireButton2 = fire
    }

    /** Set mouse position delta (adds to counters). */
    fun moveMouse(dx: Int, dy: Int) {
        val x = ((joy0dat and 0xFF) + dx) and 0xFF
        val y = (((joy0dat shr 8) and 0xFF) + dy) and 0xFF
        joy0dat = (y shl 8) or x
    }

    fun setMouseButton(left: Boolean) { fireButton1 = left }

    /** Update CIA-A PRA with fire button state (active low). */
    fun updateFireButtons(ciaA: CIA8520) {
        ciaA.pra = (ciaA.pra or 0xC0) and
            (if (fireButton1) 0xBF.toInt() else 0xFF) and
            (if (fireButton2) 0x7F else 0xFF)
    }
}
