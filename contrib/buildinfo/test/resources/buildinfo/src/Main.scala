import java.nio.file.{Files, Paths}

object Main extends App {

  val resultPath = Paths.get(args(0))

  val booleanVal: Boolean = BuildInfo.booleanVal
  val byteVal: Byte = BuildInfo.byteVal
  val shortVal: Short = BuildInfo.shortVal
  val intVal: Int = BuildInfo.intVal
  val longVal: Long = BuildInfo.longVal
  val floatVal: Float = BuildInfo.floatVal
  val doubleVal: Double = BuildInfo.doubleVal
  val charVal: Char = BuildInfo.charVal
  val stringVal: String = BuildInfo.stringVal

  val output = s"""
              |$booleanVal
              |$byteVal
              |$shortVal
              |$intVal
              |$longVal
              |$floatVal
              |$doubleVal
              |$charVal
              |$stringVal
              |""".stripMargin
  
  Files.createDirectories(resultPath.getParent)
  Files.write(resultPath, output.getBytes)

}
