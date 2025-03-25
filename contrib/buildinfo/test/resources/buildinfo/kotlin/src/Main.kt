package foo

import java.nio.file.Files
import java.nio.file.Paths

object Main {
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        val resultPath = Paths.get(args[0])
        Files.createDirectories(resultPath.parent)
        Files.write(resultPath, BuildInfo.scalaVersion.encodeToByteArray())
    }
}