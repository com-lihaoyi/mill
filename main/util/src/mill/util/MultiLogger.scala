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

  lazy val systemStreams = new SystemStreams(
    new MultiStream(logger1.systemStreams.out, logger2.systemStreams.out),
    new MultiStream(logger1.systemStreams.err, logger2.systemStreams.err),
    inStream0
  )

  def info(s: String) = {
    logger1.info(s)
    logger2.info(s)
  }
  def error(s: String) = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String) = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  def debug(s: String) = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def close() = {
    logger1.close()
    logger2.close()
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
