package mill.internal

private[mill] object Util {
  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }
}
