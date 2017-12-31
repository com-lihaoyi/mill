package better.files

import java.io.BufferedReader

/**
 * Base interface to test
 */
abstract class AbstractScanner(protected[this] val reader: BufferedReader) {
  def hasNext: Boolean
  def next(): String
  def nextInt() = next().toInt
  def nextLine() = reader.readLine()
  def close() = reader.close()
}

/**
 * Based on java.util.Scanner
 */
class JavaScanner(reader: BufferedReader) extends AbstractScanner(reader) {
  private[this] val scanner = new java.util.Scanner(reader)
  override def hasNext = scanner.hasNext
  override def next() = scanner.next()
  override def nextInt() = scanner.nextInt()
  override def nextLine() = {
    scanner.nextLine()
    scanner.nextLine()
  }
  override def close() = scanner.close()
}

/**
 * Based on StringTokenizer + resetting the iterator
 */
class IterableScanner(reader: BufferedReader) extends AbstractScanner(reader) with Iterable[String] {
  override def iterator = for {
    line <- Iterator.continually(reader.readLine()).takeWhile(_ != null)
    tokenizer = new java.util.StringTokenizer(line)
    _ <- Iterator.continually(tokenizer).takeWhile(_.hasMoreTokens)
  } yield tokenizer.nextToken()

  private[this] var current = iterator
  override def hasNext = current.hasNext
  override def next() = current.next()
  override def nextLine() = {
    current = iterator
    super.nextLine()
  }
}

/**
 * Based on a mutating var StringTokenizer
 */
class IteratorScanner(reader: BufferedReader) extends AbstractScanner(reader) with Iterator[String] {
  import java.util.StringTokenizer
  private[this] val tokenizers = Iterator.continually(reader.readLine()).takeWhile(_ != null).map(new StringTokenizer(_)).filter(_.hasMoreTokens)
  private[this] var current: Option[StringTokenizer] = None

  @inline private[this] def tokenizer(): Option[StringTokenizer] = current.find(_.hasMoreTokens) orElse {
    current = if (tokenizers.hasNext) Some(tokenizers.next()) else None
    current
  }
  override def hasNext = tokenizer().nonEmpty
  override def next() = tokenizer().get.nextToken()
  override def nextLine() = {
    current = None
    super.nextLine()
  }
}

/**
 * Based on java.io.StreamTokenizer
 */
class StreamingScanner(reader: BufferedReader) extends AbstractScanner(reader) with Iterator[String] {
  import java.io.StreamTokenizer
  private[this] val in = new StreamTokenizer(reader)

  override def hasNext = in.ttype != StreamTokenizer.TT_EOF
  override def next() = {
    in.nextToken()
    in.sval
  }
  override def nextInt() = nextDouble().toInt
  def nextDouble() = {
    in.nextToken()
    in.nval
  }
}

/**
 * Based on a reusable StringBuilder
 */
class StringBuilderScanner(reader: BufferedReader) extends AbstractScanner(reader) with Iterator[String] {
  private[this] val chars = reader.chars
  private[this] val buffer = new StringBuilder()

  override def next() = {
    buffer.clear()
    while (buffer.isEmpty && hasNext) {
      chars.takeWhile(c => !c.isWhitespace).foreach(buffer += _)
    }
    buffer.toString()
  }
  override def hasNext = chars.hasNext
}

/**
 * Scala version of the ArrayBufferScanner
 */
class CharBufferScanner(reader: BufferedReader) extends AbstractScanner(reader) with Iterator[String] {
  private[this] val chars = reader.chars
  private[this] var buffer = Array.ofDim[Char](1<<4)

  override def next() = {
    var pos = 0
    while (pos == 0 && hasNext) {
      for {
        c <- chars.takeWhile(c => c != ' ' && c != '\n')
      } {
        if (pos == buffer.length) buffer = java.util.Arrays.copyOf(buffer, 2 * pos)
        buffer(pos) = c
        pos += 1
      }
    }
    String.copyValueOf(buffer, 0, pos)
  }
  override def hasNext = chars.hasNext
}

/**
  * Scanner using https://github.com/williamfiset/FastJavaIO
  */
class FastJavaIOScanner(reader: BufferedReader) extends AbstractScanner(reader) {
  protected def is: java.io.InputStream = new org.apache.commons.io.input.ReaderInputStream(reader, defaultCharset)

  private[this] val fastReader = new fastjavaio.InputReader(is)

  override def hasNext = true     //TODO: https://github.com/williamfiset/FastJavaIO/issues/3
  override def next() = fastReader.readStr()
  override def nextInt() = fastReader.readInt()
  override def nextLine() = fastReader.readLine()
}

/**
  * Same as FastJavaIOScanner but uses better-files's Reader => InputStream
  */
class FastJavaIOScanner2(reader: BufferedReader) extends FastJavaIOScanner(reader) {
  override def is = reader.toInputStream
}

/**
  * Based on the better-files implementation
  */
class BetterFilesScanner(reader: BufferedReader) extends AbstractScanner(reader) {
  private[this] val scanner = Scanner(reader)
  override def hasNext = scanner.hasNext
  override def next() = scanner.next
  override def nextLine() = scanner.nextLine()
}
