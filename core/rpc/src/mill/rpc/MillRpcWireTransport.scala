package mill.rpc

import pprint.{TPrint, TPrintColors}
import upickle.default.{Reader, Writer}

import java.util.concurrent.BlockingQueue

trait MillRpcWireTransport extends AutoCloseable {

  /** Human-readable name of the wire. */
  def name: String

  /** Reads one raw message from the wire. */
  def read(): Option[String]

  /** Helper that reads a message from the wire and tries to parse it, logging along the way. */
  def readAndTryToParse[A: Reader](log: String => Unit)(using typeName: TPrint[A]): Option[A] =
    readAndTryToParse[A](typeName.render(using TPrintColors.Colors).render, log)

  /** Helper that reads a message from the wire and tries to parse it, logging along the way. */
  def readAndTryToParse[A: Reader](typeName: String, log: String => Unit): Option[A] = {
    read() match {
      case None =>
        log("Transport wire broken.")
        None

      case Some(line) =>
        log(s"Received, will try to parse as $typeName: $line")
        val parsed = upickle.default.read(line)
        log(s"Parsed: ${pprint.apply(parsed)}")
        Some(parsed)
    }
  }

  /** Writes one raw message to the wire. */
  def write(message: String): Unit

  /** Helper that writes a message to the wire, logging along the way. */
  def writeSerialized[A: Writer](message: A, log: String => Unit): Unit = {
    log(s"Serializing message to be sent: ${pprint.apply(message)}")
    val serialized = upickle.default.write(message)
    log(s"Sending: $serialized")
    write(serialized)
  }
}

object MillRpcWireTransport {
  case object ViaStdinAndStdout extends MillRpcWireTransport {
    def name: String = "stdin/out"

    def read(): Option[String] = Option(Console.in.readLine())

    def write(message: String): Unit = {
      val out = Console.out
      out.println(message)
      out.flush()
    }

    override def close(): Unit = {
      // Do nothing
    }
  }

  case class ViaStdinAndStdoutOfSubprocess(subprocess: os.SubProcess) extends MillRpcWireTransport {
    def name: String = s"stdin/out of subprocess (PID ${subprocess.wrapped.pid()})"

    def read(): Option[String] = Option(subprocess.stdout.readLine())

    def write(message: String): Unit = {
      subprocess.stdin.writeLine(message)
      subprocess.stdin.flush()
    }

    override def close(): Unit = {
      subprocess.close()
    }
  }

  case class ViaBlockingQueue(queue: BlockingQueue[String]) extends MillRpcWireTransport {
    override def name: String = "in-process blocking queue"

    override def read(): Option[String] = Some(queue.take())

    override def write(message: String): Unit = queue.put(message)

    override def close(): Unit = queue.clear()
  }
}
