package better.files

import java.io.{OutputStream, Writer}
import java.nio.charset.{Charset, CharsetDecoder, CodingErrorAction}
import java.nio.{ByteBuffer, CharBuffer}

import scala.annotation.tailrec

/**
  * Code ported from Java to Scala:
  * https://github.com/apache/commons-io/blob/d357d9d563c4a34fa2ab3cdc68221c851a9de4f5/src/main/java/org/apache/commons/io/output/WriterOutputStream.java
  */
class WriterOutputStream(writer: Writer, decoder: CharsetDecoder, bufferSize: Int, flushImmediately: Boolean) extends OutputStream {

  /**
    * CharBuffer used as output for the decoder
    */
  private[this] val decoderOut = CharBuffer.allocate(bufferSize)

  /**
    * ByteBuffer used as output for the decoder. This buffer can be small
    * as it is only used to transfer data from the decoder to the buffer provided by the caller.
    */
  private[this] val decoderIn = ByteBuffer.allocate(bufferSize>>4)

  def this(writer: Writer, bufferSize: Int = defaultBufferSize, flushImmediately: Boolean = false)(implicit charset: Charset = defaultCharset) =
    this(writer = writer, decoder = charset.newDecoder.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith("?"), bufferSize = bufferSize, flushImmediately = flushImmediately)

  override def write(b: Array[Byte], off: Int, len: Int) = {
    @tailrec def loop(off: Int, len: Int): Unit = if (len > 0) {
      val c = decoderIn.remaining min len
      decoderIn.put(b, off, c)
      processInput(endOfInput = false)
      loop(off + c, len - c)
    }
    loop(off, len)
    if (flushImmediately) flushOutput()
  }

  override def write(b: Int) = write(Array(b.toByte))

  override def flush() = {
    flushOutput()
    writer.flush()
  }

  override def close() = {
    processInput(endOfInput = true)
    flushOutput()
    writer.close()
  }

  private[this] def processInput(endOfInput: Boolean) = {
    decoderIn.flip()
    @tailrec def loop(): Unit = {
      val coderResult = decoder.decode(decoderIn, decoderOut, endOfInput)
      if (coderResult.isOverflow) {
        flushOutput()
        loop()
      } else {
        assert(coderResult.isUnderflow, "decoder is configured to replace malformed input and unmappable characters")
      }
    }
    loop()
    decoderIn.compact()
  }

  private[this] def flushOutput(): Unit = {
    if (decoderOut.position > 0) {
      writer.write(decoderOut.array, 0, decoderOut.position)
      val _ = decoderOut.rewind()
    }
  }
}
