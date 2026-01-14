package qux

public class Qux {
    companion object {
        val value = 31337

        @JvmStatic
        fun main(args: Array<String>) {
            println("Qux.value: " + Qux.value)
        }
    }
}
