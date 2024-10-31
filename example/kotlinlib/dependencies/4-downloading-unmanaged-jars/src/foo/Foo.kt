package foo

import com.williamfiset.fastjavaio.InputReader

import java.io.FileInputStream
import java.io.IOException

fun main(args: Array<String>) {
    val filePath = args[0]
    val fi = try {
        InputReader(FileInputStream(filePath))
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }

    var line: String? = fi?.nextLine()
    while (line != null) {
        println(line)
        line = fi?.nextLine()
    }

    try {
        fi?.close()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}
