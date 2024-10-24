package foo

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

fun main(args: Array<String>) {
    val value = ObjectMapper().writeValueAsString(args)
    println("JSONified using Jackson: $value")
}
