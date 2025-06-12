package mill.internal

import java.io.{IOException, InputStream, OutputStream}

/**
 * Fork of `java.io.Piped{Input,Output}Stream` that allows writes to come from
 * separate threads. Really the same logic just with the assertions on thread
 * liveness removed, added some synchronization to ensure atomic writes, and
 * somewhat cleaned up as a single object rather than two loose objects you have
 * to connect together.
 */
private[mill] class PipeStreams(val bufferSize: Int = 1024) { pipe =>

  private var closedByWriter = false
  @volatile private var closedByReader = false
  private val buffer: Array[Byte] = new Array[Byte](bufferSize)
  private var in: Int = -1
  private var out = 0

  val input: InputStream = Input
  private object Input extends InputStream {

    private[PipeStreams] def receive(b: Int): Unit = synchronized {
      checkStateForReceive()
      if (in == out) awaitSpace()
      if (in < 0) {
        in = 0
        out = 0
      }
      buffer(in) = (b & 0xff).toByte
      in += 1
      if (in >= buffer.length) in = 0
      notifyAll()
    }

    private[PipeStreams] def receive(b: Array[Byte], off0: Int, len: Int): Unit = synchronized {
      var off = off0
      checkStateForReceive()
      var bytesToTransfer = len
      while (bytesToTransfer > 0) {
        if (in == out) awaitSpace()
        var nextTransferAmount = 0
        if (out < in) nextTransferAmount = buffer.length - in
        else if (in < out) {
          if (in == -1) {
            in = 0
            out = 0
            nextTransferAmount = buffer.length - in
          } else {
            nextTransferAmount = out - in
          }
        }
        if (nextTransferAmount > bytesToTransfer) nextTransferAmount = bytesToTransfer
        assert(nextTransferAmount > 0)
        System.arraycopy(b, off, buffer, in, nextTransferAmount)
        bytesToTransfer -= nextTransferAmount
        off += nextTransferAmount
        in += nextTransferAmount
        if (in >= buffer.length) in = 0
      }
      notifyAll()
    }

    private def checkStateForReceive(): Unit = {
      if (closedByWriter || closedByReader) throw new IOException("Pipe closed")
    }

    private def awaitSpace(): Unit = {
      while (in == out) {
        checkStateForReceive()
        /* full: kick any waiting readers */
        notifyWaitCatch()
      }
    }

    private[internal] def receivedLast(): Unit = synchronized {
      closedByWriter = true
      notifyAll()
    }

    private def notifyWaitCatch() = {
      notifyAll()
      try wait(1000)
      catch {
        case _: InterruptedException =>
          throw new java.io.InterruptedIOException
      }
    }

    override def read(): Int = synchronized {
      if (closedByReader) throw new IOException("Pipe closed")
      while (in < 0) {
        if (closedByWriter) return -1 /* closed by writer, return EOF */

        /* might be a writer waiting */
        notifyWaitCatch()
      }

      val ret = buffer(out) & 0xff
      out += 1
      if (out >= buffer.length) out = 0
      if (in == out) in = -1 /* now empty */

      ret
    }

    override def read(b: Array[Byte], off: Int, len0: Int): Int = synchronized {
      var len = len0
      if (b == null) throw new NullPointerException
      else if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException
      else if (len == 0) return 0
      /* possibly wait on the first character */
      val c = read
      if (c < 0) return -1
      b(off) = c.toByte
      var rlen = 1
      while ((in >= 0) && (len > 1)) {
        var available = 0
        if (in > out) available = Math.min(buffer.length - out, in - out)
        else available = buffer.length - out
        // A byte is read beforehand outside the loop

        if (available > (len - 1)) available = len - 1
        System.arraycopy(buffer, out, b, off + rlen, available)

        out += available
        rlen += available
        len -= available
        if (out >= buffer.length) out = 0
        if (in == out) in = -1 /* now empty */

      }
      rlen
    }

    override def available(): Int = synchronized {
      if (in < 0) 0
      else if (in == out) buffer.length
      else if (in > out) in - out
      else in + buffer.length - out
    }

    override def close(): Unit = {
      closedByReader = true
      synchronized {
        in = -1
      }
    }

    override def readNBytes(b: Array[Byte], off: Int, len: Int): Int =
      synchronized { super.readNBytes(b, off, len) }

    override def readNBytes(len: Int): Array[Byte] = synchronized { super.readNBytes(len) }

    override def readAllBytes(): Array[Byte] = synchronized { super.readAllBytes() }

    override def transferTo(out: OutputStream): Long = synchronized { super.transferTo(out) }
  }

  val output: OutputStream = Output
  private object Output extends OutputStream {
    override def write(b: Int): Unit = synchronized { Input.receive(b) }
    override def write(b: Array[Byte]): Unit = synchronized { super.write(b) }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = synchronized {
      if (b == null) throw new NullPointerException
      else if (
        (off < 0) || (off > b.length) || (len < 0) || ((off + len) > b.length) || ((off + len) < 0)
      ) {
        throw new IndexOutOfBoundsException
      } else if (len != 0) Input.receive(b, off, len)
    }

    override def flush(): Unit = ()
    override def close(): Unit = Input.receivedLast()
  }
}
