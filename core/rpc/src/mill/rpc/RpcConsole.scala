package mill.rpc

import java.io.PrintStream

/** Messages for simulating a simple console via RPC. */
trait RpcConsole {
  def print(s: String): Unit
  def println(s: String): Unit = print(s + "\n")
  def flush(): Unit
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
