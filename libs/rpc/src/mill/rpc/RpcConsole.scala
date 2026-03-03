package mill.rpc

import java.io.{OutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** Messages for simulating a simple console via RPC. */
trait RpcConsole { self =>
  def print(s: String): Unit
  def println(s: String): Unit = print(s + "\n")
  def flush(): Unit

  def asStream: OutputStream = new RpcConsole.Utf8OutputStream(self)
}
object RpcConsole {
  enum Message derives upickle.ReadWriter {
    case Print(s: String)
    case Flush
  }

  def create(send: Message => Unit): RpcConsole = new {
    override def print(s: String): Unit = send(Message.Print(s))
    override def flush(): Unit = send(Message.Flush)
  }

  def stdoutHandler(msg: Message): Unit = printStreamHandler(Console.out, msg)
  def stderrHandler(msg: Message): Unit = printStreamHandler(Console.err, msg)

  def printStreamHandler(printStream: PrintStream, msg: Message): Unit = msg match {
    case Message.Print(s) => printStream.print(s)
    case Message.Flush => printStream.flush()
  }

  /**
   * An OutputStream that buffers incomplete UTF-8 byte sequences across writes,
   * forwarding only complete characters as strings to the underlying [[RpcConsole]].
   *
   * This is needed because upstream byte sources (e.g. [[mill.constants.ProxyStream]])
   * may split writes at arbitrary byte boundaries, potentially in the middle of
   * multi-byte UTF-8 characters. Without buffering, such partial sequences would be
   * converted to the Unicode replacement character (U+FFFD).
   */
  class Utf8OutputStream(console: RpcConsole) extends OutputStream {
    // Pending bytes from an incomplete UTF-8 sequence at the end of the last write
    private var pending: Array[Byte] = Array.emptyByteArray

    override def write(b: Int): Unit = write(Array(b.toByte), 0, 1)

    override def write(b: Array[Byte]): Unit = write(b, 0, b.length)

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      if (len == 0) return

      // Combine any pending bytes with the new data
      val data =
        if (pending.isEmpty) java.util.Arrays.copyOfRange(b, off, off + len)
        else {
          val combined = new Array[Byte](pending.length + len)
          System.arraycopy(pending, 0, combined, 0, pending.length)
          System.arraycopy(b, off, combined, pending.length, len)
          combined
        }

      // Find the boundary after the last complete UTF-8 character
      val boundary = lastCompleteUtf8Boundary(data)

      if (boundary > 0) {
        console.print(new String(data, 0, boundary, StandardCharsets.UTF_8))
      }

      pending =
        if (boundary < data.length) java.util.Arrays.copyOfRange(data, boundary, data.length)
        else Array.emptyByteArray
    }

    override def flush(): Unit = {
      // Flush any pending bytes (they are likely malformed; let String handle them)
      if (pending.nonEmpty) {
        console.print(new String(pending, StandardCharsets.UTF_8))
        pending = Array.emptyByteArray
      }
      console.flush()
    }

    override def close(): Unit = flush()
  }

  /**
   * Returns the index in `data` after the last complete UTF-8 character.
   * Any trailing incomplete multi-byte sequence is excluded so it can be
   * buffered for the next write.
   */
  private[rpc] def lastCompleteUtf8Boundary(data: Array[Byte]): Int = {
    val len = data.length
    if (len == 0) return 0

    // Walk backwards past continuation bytes (10xxxxxx)
    var i = len - 1
    while (i >= 0 && (data(i) & 0xc0) == 0x80) i -= 1

    // If we didn't find a leading byte, all bytes are continuations (malformed)
    if (i < 0) return len

    val leadByte = data(i) & 0xff
    val expectedLen =
      if ((leadByte & 0x80) == 0) 1 // 0xxxxxxx  — ASCII
      else if ((leadByte & 0xe0) == 0xc0) 2 // 110xxxxx — 2-byte
      else if ((leadByte & 0xf0) == 0xe0) 3 // 1110xxxx — 3-byte
      else if ((leadByte & 0xf8) == 0xf0) 4 // 11110xxx — 4-byte
      else 1 // Invalid leading byte; treat as single byte

    val actualLen = len - i
    if (actualLen >= expectedLen) len // last sequence is complete
    else i // last sequence is incomplete — exclude it
  }
}
