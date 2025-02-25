package foo

import com.fasterxml.jackson.databind.ObjectMapper

fun main(args: Array<String>) {
    val value = ObjectMapper().writeValueAsString(args)
    println("JSONified using Jackson: $value")
}
