package mill.rpc

import pprint.{TPrint, TPrintColors}
import upickle.{Reader, Writer}

import java.io.{BufferedReader, PrintStream}
import scala.annotation.tailrec
class MillRpcWireTransport(
    val name: String,
    serverToClient: BufferedReader,
    clientToServer: PrintStream,
    writeSynchronizer: AnyRef,
    val logDir: Option[os.Path] = None
) extends AutoCloseable {
  def read(): Option[String] = Option(serverToClient.readLine())

  def write(message: String): Unit = {
    writeSynchronizer.synchronized {
      clientToServer.println(message)
      clientToServer.flush()
    }
  }

  /**
   * Writes an empty line as a heartbeat and returns whether the write succeeded.
   * Unlike `write("")`, this checks for errors since `PrintStream` swallows exceptions internally.
   */
  def writeHeartbeat(): Boolean = {
    writeSynchronizer.synchronized {
      clientToServer.println("")
      clientToServer.flush()
      !clientToServer.checkError()
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
      case Some("") => readAndTryToParse[A](typeName, log, firstInvocation = false)
      case Some(line) => Some(upickle.read(line))
    }
  }

  /** Helper that writes a message to the wire, logging along the way. */
  def writeSerialized[A: Writer](message: A, log: String => Unit): Unit = {
    write(upickle.write(message))
  }
}
