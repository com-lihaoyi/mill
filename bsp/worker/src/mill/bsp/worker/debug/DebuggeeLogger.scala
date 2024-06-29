package mill.bsp.worker.debug

import ch.epfl.scala.debugadapter._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.io.PrintStream

class DebuggeeLogger(listener: DebuggeeListener, systemOut: PrintStream, systemErr: PrintStream, debugFunc: String => Unit)
    extends Logger {

  private val initialized = new AtomicBoolean(false)

  def debug(msg: => String): Unit =
    listener.err(s"[error] $msg")

  def info(msg: => String): Unit = {
    val message = msg

    // Expect the first log to be JDI notification since debuggee runs with `quiet=n` JDI option
    if (message.startsWith(DebuggeeLogger.JDINotificationPrefix)) {
      if (initialized.compareAndSet(false, true)) {
        val port = Integer.parseInt(msg.drop(DebuggeeLogger.JDINotificationPrefix.length))
        val address = new InetSocketAddress("127.0.0.1", port)
        listener.onListening(address)
      }
    } else {
      listener.out(message)
    }
  }
  def warn(msg: => String): Unit = {
    listener.out(msg)
  }
  def error(msg: => String): Unit = {
    listener.err(msg)
  }
  def trace(t: => Throwable): Unit = t.printStackTrace()
}
object DebuggeeLogger {
  final val JDINotificationPrefix = "Listening for transport dt_socket at address: "
}
