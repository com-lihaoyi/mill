package better.files

import java.io.{InputStream, Reader}
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CharsetEncoder, CoderResult, CodingErrorAction}

import scala.annotation.tailrec

/**
  * Code ported from Java to Scala:
  * https://github.com/apache/commons-io/blob/c0eb48f7e83987c5ed112b82f0d651aff5149ae4/src/main/java/org/apache/commons/io/input/ReaderInputStream.java
  */
class ReaderInputStream(reader: Reader, encoder: CharsetEncoder, bufferSize: Int) extends InputStream {

  def this(reader: Reader, bufferSize: Int = defaultBufferSize)(implicit charset: Charset = defaultCharset) =
    this(reader = reader, encoder = charset.newEncoder.onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE), bufferSize = bufferSize)

  /**
    * CharBuffer used as input for the decoder. It should be reasonably
    * large as we read data from the underlying Reader into this buffer.
    */
  private[this] val encoderIn = CharBuffer.allocate(bufferSize).flip().asInstanceOf[CharBuffer]

  /**
    * ByteBuffer used as output for the decoder. This buffer can be small
    * as it is only used to transfer data from the decoder to the buffer provided by the caller.
    */
  private[this] val encoderOut = ByteBuffer.allocate(bufferSize>>4).flip().asInstanceOf[ByteBuffer]

  private[this] var lastCoderResult = CoderResult.UNDERFLOW
  private[this] var endOfInput = false

  private[this] def fillBuffer() = {
    assert(!endOfInput)
    if (lastCoderResult.isUnderflow) {
      val position = encoderIn.compact().position
      //  We don't use Reader#read(CharBuffer) here because it is more efficient to write directly to the underlying char array
      // since the default implementation copies data to a temporary char array anyway
      reader.read(encoderIn.array, position, encoderIn.remaining) match {
        case EOF => endOfInput = true
        case c => encoderIn.position(position + c)
      }
      encoderIn.flip()
    }
    lastCoderResult = encoder.encode(encoderIn, encoderOut.compact(), endOfInput)
    encoderOut.flip()
  }

  override def read(b: Array[Byte], off: Int, len: Int) = {
    if (len < 0 || off < 0 || (off + len) > b.length) throw new IndexOutOfBoundsException("Array Size=" + b.length + ", offset=" + off + ", length=" + len)
    if (len == 0) {
      0 // Always return 0 if len == 0
    } else {
      var read = 0
      @tailrec def loop(off: Int, len: Int): Unit = if (len > 0) {
        if (encoderOut.hasRemaining) {
          val c = encoderOut.remaining min len
          encoderOut.get(b, off, c)
          read += c
          loop(off + c, len - c)
        } else if (!endOfInput) {
          fillBuffer()
          loop(off, len)
        }
      }
      loop(off, len)
      if (read == 0 && endOfInput) EOF else read
    }
  }

  @tailrec final override def read() = {
    if (encoderOut.hasRemaining) {
      encoderOut.get & 0xFF
    } else if (endOfInput) {
      EOF
    } else {
      fillBuffer()
      read()
    }
  }

  override def close() = reader.close()
}
