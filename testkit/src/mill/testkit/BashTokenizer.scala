package mill.testkit
import java.util.ArrayList
import java.util.List
import scala.jdk.CollectionConverters._

object BashTokenizer {
  def tokenize(string: CharSequence): Seq[String] = {
    // taken from https://stackoverflow.com/a/20725050
    val tokens: List[String] = ArrayList[String]()
    var escaping = false
    var quoteChar = ' '
    var quoting = false
    var lastCloseQuoteIndex = Integer.MIN_VALUE
    val current = StringBuilder()
    for (i <- 0 until string.length()) {
      val c = string.charAt(i)
      if (escaping) {
        current.append('\\')
        current.append(c)
        escaping = false
      } else if (c == '\\' && !(quoting && quoteChar == '\'')) {
        escaping = true
      } else if (quoting && c == quoteChar) {
        quoting = false
        lastCloseQuoteIndex = i
      } else if (!quoting && (c == '\'' || c == '"')) {
        quoting = true
        quoteChar = c
      } else if (!quoting && Character.isWhitespace(c)) {
        if (current.length() > 0 || lastCloseQuoteIndex == (i - 1)) {
          tokens.add(current.toString)
          current.setLength(0)
        }
      } else {
        current.append(c)
      }
    }
    if (current.length() > 0 || lastCloseQuoteIndex == (string.length() - 1)) {
      tokens.add(current.toString)
    }
    tokens.asScala.toSeq
  }
}
