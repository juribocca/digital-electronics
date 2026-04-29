import com.namirial.trust.electronics.core.*
import com.namirial.trust.electronics.combinational.*
import com.namirial.trust.electronics.clock.*
import com.namirial.trust.electronics.sequential.*

fun main() {
    /*val a = Input()
    val b = Input()
    val cIn = Input()
    val adder = FullAdder(a, b, cIn)

    println("Full Adder Truth Table:")
    println("A  B  Cin | Sum  Cout")
    for (va in listOf(false, true)) {
        for (vb in listOf(false, true)) {
            for (vc in listOf(false, true)) {
                a.value = va; b.value = vb; cIn.value = vc
                val ai = if (va) 1 else 0; val bi = if (vb) 1 else 0; val ci = if (vc) 1 else 0
                val s = if (adder.sum.value) 1 else 0; val c = if (adder.carryOut.value) 1 else 0
                println("$ai  $bi  $ci   |  $s    $c")
            }
        }
    }*/

    val address = List(4) { Input() }  // cosi' e' selezionato l'indirizzo 0000, in binario
    val dataIn  =
        //List(8) { Input() }.toInputByte()
         InputByte()
        // List(8) { Input() }
    val writeEnable = Input()
    // val gr = RAM(address, dataIn.bits, writeEnable)
    // val gr = RAM(address, dataIn.bits, writeEnable)
    val addr = InputAddress(2)       // 16-bit address (64KB)
    val data = InputByte()           // 8-bit data
    val we = Input()
    val gr = ByteAddressedRAM(addr, data, we)
    // data.bits[0].value = true
    // data.bits[7].value = true



    writeEnable.value = true
    dataIn.bits.forEach { it.value = true }
    gr.clock()
    // gr.dataOut.toOutputByte().toByte()
    // gr.typedDataOut.toByte()

}
