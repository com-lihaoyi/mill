package mill.rpc

import pprint.{TPrint, TPrintColors}
import upickle.default.{Reader, Writer}

import java.io.{BufferedReader, PrintStream}
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
    log(s"Trying to read $typeName")
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

  class ViaBlockingQueues(inQueue: BlockingQueue[String], outQueue: BlockingQueue[String])
      extends MillRpcWireTransport {
    @volatile private var closed = false

    override def name: String = "in-process blocking queue"

    override def read(): Option[String] = {
      try if (closed) None else Some(inQueue.take())
      catch { case _: InterruptedException => None }
    }

    override def write(message: String): Unit = outQueue.put(message)

    override def close(): Unit = {
      outQueue.clear()
      closed = true
    }
  }

  /**
   * @param serverToClient server to client stream
   * @param clientToServer client to server stream
   */
  class ViaStreams(
      override val name: String,
      serverToClient: BufferedReader,
      clientToServer: PrintStream
  ) extends MillRpcWireTransport {
    override def read(): Option[String] = Option(serverToClient.readLine())

    override def write(message: String): Unit = {
      clientToServer.println(message)
      clientToServer.flush()
    }

    override def close(): Unit = {
      serverToClient.close()
      clientToServer.close()
    }
  }
}
