package mill.api.internal

object Util {

  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }

  def renderSecondsSuffix(millis: Long): String =
    (millis / 1000).toInt match {
      case 0 => ""
      case n => s" ${n}s"
    }

  def formatError(
      fileName: String,
      lineNum: Int,
      colNum: Int,
      lineContent: String,
      message: String,
      pointerLength: Int = 1,
      shade: String => String = identity
  ): String = {
    // Create pointer line
    val pointer = if (colNum > 0) " " * (colNum - 1) + shade("^" * pointerLength) else ""

    val header =
      if (lineNum >= 0 && colNum >= 0)
        s"${shade(fileName)}:${shade(lineNum.toString)}:${shade(colNum.toString)}"
      else shade(fileName)

    s"""$header
       |$lineContent
       |$pointer
       |$message""".stripMargin
  }
}
