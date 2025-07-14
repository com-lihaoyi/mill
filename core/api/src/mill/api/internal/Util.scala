package mill.api.internal

object Util {

  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }

  def renderSecondsSuffix(millis: Long, @com.lihaoyi.unroll dummy: Int = 0) = (millis / 1000).toInt match {
    case 0 => ""
    case n => s" ${n}s"
  }
}
