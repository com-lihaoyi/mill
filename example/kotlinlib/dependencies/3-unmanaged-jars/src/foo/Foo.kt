package foo

import com.grack.nanojson.JsonParser

fun main(args: Array<String>) {
    val jsonString = args[0]
    val jsonObj = JsonParser.`object`().from(jsonString)

    jsonObj.entries.forEach { println("Key: ${it.key}, Value: ${it.value}") }
}
