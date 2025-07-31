package mill.rpc

import java.io.{OutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** Messages for simulating a simple console via RPC. */
trait RpcConsole { self =>
  def print(s: String): Unit
  def println(s: String): Unit = print(s + "\n")
  def flush(): Unit

  def asStream: OutputStream = new {
    override def write(b: Int): Unit = self.print(b.toChar.toString)
    override def write(b: Array[Byte]): Unit = self.print(String(b, StandardCharsets.UTF_8))
    override def write(b: Array[Byte], off: Int, len: Int): Unit = self.print(String(b, off, len, StandardCharsets.UTF_8))
    override def flush(): Unit = self.flush()
  }
}
object RpcConsole {
  enum Message derives upickle.default.ReadWriter {
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
}
