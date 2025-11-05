package mill.rpc

import pprint.{TPrint, TPrintColors}
import upickle.{Reader, Writer}

import java.io.{BufferedReader, PrintStream}
import java.util.concurrent.BlockingQueue
import scala.annotation.tailrec
class MillRpcWireTransport(
    val name: String,
    serverToClient: BufferedReader,
    clientToServer: PrintStream,
    writeSynchronizer: AnyRef
) extends AutoCloseable {
  def read(): Option[String] = Option(serverToClient.readLine())

  def write(message: String): Unit = {
    writeSynchronizer.synchronized {
      clientToServer.println(message)
      clientToServer.flush()
    }
  }

  def close(): Unit = {
    serverToClient.close()
    clientToServer.close()
  }

  def readAndTryToParse[A: Reader](log: String => Unit)(using typeName: TPrint[A]): Option[A] =
    readAndTryToParse[A](typeName.render(using TPrintColors.Colors).render, log)

  /** Helper that reads a message from the wire and tries to parse it, logging along the way. */
  @tailrec final def readAndTryToParse[A: Reader](
                                                   typeName: String,
                                                   log: String => Unit,
                                                   firstInvocation: Boolean = true
                                                 ): Option[A] = {
    // Only log on the first invocation, not when we get a heartbeat
    if (firstInvocation) log(s"Trying to read $typeName")

    read() match {
      case None =>
        log("Transport wire broken.")
        None

      // Empty line means a heartbeat message
      case Some("") =>
        readAndTryToParse[A](typeName, log, firstInvocation = false)

      case Some(line) =>
        log(s"Received, will try to parse as $typeName: $line")
        val parsed = upickle.read(line)
        log(s"Parsed: ${pprint.apply(parsed)}")
        Some(parsed)
    }
  }

  /** Helper that writes a message to the wire, logging along the way. */
  def writeSerialized[A: Writer](message: A, log: String => Unit): Unit = {
    write(upickle.write(message))
  }
}

