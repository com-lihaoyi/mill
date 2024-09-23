package mill.util

import mill.api.{Logger, SystemStreams}

import java.io.{InputStream, OutputStream, PrintStream}

class MultiLogger(
    val colored: Boolean,
    val logger1: Logger,
    val logger2: Logger,
    val inStream0: InputStream,
    override val debugEnabled: Boolean
) extends Logger {
  override def toString = s"MultiLogger($logger1, $logger2)"
  lazy val systemStreams = new SystemStreams(
    new MultiStream(logger1.systemStreams.out, logger2.systemStreams.out),
    new MultiStream(logger1.systemStreams.err, logger2.systemStreams.err),
    inStream0
  )

  def info(s: String): Unit = {
    logger1.info(s)
    logger2.info(s)
  }
  def error(s: String): Unit = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String): Unit = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  override def ticker(identifier: String, identSuffix: String, message: String): Unit = {
    logger1.ticker(identifier, identSuffix, message)
    logger2.ticker(identifier, identSuffix, message)
  }

  def debug(s: String): Unit = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def close(): Unit = {
    logger1.close()
    logger2.close()
  }
  override def reportPrefix(s: String): Unit = {
    logger1.reportPrefix(s)
    logger2.reportPrefix(s)
  }

  override def rawOutputStream: PrintStream = systemStreams.out

  override def endTicker(): Unit = {
    logger1.endTicker()
    logger2.endTicker()
  }
  override def globalTicker(s: String): Unit = {
    logger1.globalTicker(s)
    logger2.globalTicker(s)
  }
}

class MultiStream(stream1: OutputStream, stream2: OutputStream)
    extends PrintStream(new OutputStream {
      def write(b: Int): Unit = {
        stream1.write(b)
        stream2.write(b)
      }
      override def write(b: Array[Byte]): Unit = {
        stream1.write(b)
        stream2.write(b)
      }
      override def write(b: Array[Byte], off: Int, len: Int) = {
        stream1.write(b, off, len)
        stream2.write(b, off, len)
      }
      override def flush() = {
        stream1.flush()
        stream2.flush()
      }
      override def close() = {
        stream1.close()
        stream2.close()
      }
    })
