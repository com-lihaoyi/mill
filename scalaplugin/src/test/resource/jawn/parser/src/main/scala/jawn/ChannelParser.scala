package jawn

import java.lang.Integer.{ bitCount, highestOneBit }
import java.io.{File, FileInputStream}
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

object ChannelParser {

  final val DefaultBufferSize = 1048576

  final val ParseAsStringThreshold = 20 * 1048576

  def fromFile[J](f: File, bufferSize: Int = DefaultBufferSize): SyncParser[J] =
    if (f.length < ParseAsStringThreshold) {
      val bytes = new Array[Byte](f.length.toInt)
      val fis = new FileInputStream(f)
      fis.read(bytes)
      new StringParser[J](new String(bytes, "UTF-8"))
    } else {
      new ChannelParser[J](new FileInputStream(f).getChannel, bufferSize)
    }

  def fromChannel[J](ch: ReadableByteChannel, bufferSize: Int = DefaultBufferSize): ChannelParser[J] =
    new ChannelParser[J](ch, bufferSize)

  /**
   * Given a desired buffer size, find the closest positive
   * power-of-two larger than that size.
   *
   * This method throws an exception if the given values are negative
   * or too large to have a valid power of two.
   */
  def computeBufferSize(x: Int): Int =
    if (x < 0) {
      throw new IllegalArgumentException("negative bufferSize ($x)")
    } else if (x > 0x40000000) {
      throw new IllegalArgumentException("bufferSize too large ($x)")
    } else if (bitCount(x) == 1) {
      x
    } else {
      highestOneBit(x) << 1
    }
}

/**
 * Basic file parser.
 *
 * Given a file name this parser opens it, chunks the data, and parses
 * it.
 */
final class ChannelParser[J](ch: ReadableByteChannel, bufferSize: Int) extends SyncParser[J] with ByteBasedParser[J] {

  var Bufsize: Int = ChannelParser.computeBufferSize(bufferSize)
  var Mask: Int = Bufsize - 1
  var Allsize: Int = Bufsize * 2

  // these are the actual byte arrays we'll use
  private var curr = new Array[Byte](Bufsize)
  private var next = new Array[Byte](Bufsize)

  // these are the bytecounts for each array
  private var ncurr = ch.read(ByteBuffer.wrap(curr))
  private var nnext = ch.read(ByteBuffer.wrap(next))

  var line = 0
  private var pos = 0
  protected[this] final def newline(i: Int): Unit = { line += 1; pos = i }
  protected[this] final def column(i: Int): Int = i - pos

  protected[this] final def close(): Unit = ch.close()

  /**
   * Swap the curr and next arrays/buffers/counts.
   *
   * We'll call this in response to certain reset() calls. Specifically, when
   * the index provided to reset is no longer in the 'curr' buffer, we want to
   * clear that data and swap the buffers.
   */
  protected[this] final def swap(): Unit = {
    var tmp = curr; curr = next; next = tmp
    var ntmp = ncurr; ncurr = nnext; nnext = ntmp
  }

  protected[this] final def grow(): Unit = {
    val cc = new Array[Byte](Allsize)
    System.arraycopy(curr, 0, cc, 0, Bufsize)
    System.arraycopy(next, 0, cc, Bufsize, Bufsize)

    curr = cc
    ncurr = ncurr + nnext
    next = new Array[Byte](Allsize)
    nnext = ch.read(ByteBuffer.wrap(next))

    Bufsize = Allsize
    Mask = Allsize - 1
    Allsize *= 2
  }

  /**
   * If the cursor 'i' is past the 'curr' buffer, we want to clear the
   * current byte buffer, do a swap, load some more data, and
   * continue.
   */
  protected[this] final def reset(i: Int): Int =
    if (i >= Bufsize) {
      swap()
      nnext = ch.read(ByteBuffer.wrap(next))
      pos -= Bufsize
      i - Bufsize
    } else {
      i
    }

  protected[this] final def checkpoint(state: Int, i: Int, stack: List[FContext[J]]): Unit = ()

  /**
   * This is a specialized accessor for the case where our underlying
   * data are bytes not chars.
   */
  protected[this] final def byte(i: Int): Byte =
    if (i < Bufsize) curr(i)
    else if (i < Allsize) next(i & Mask)
    else { grow(); byte(i) }

  /**
   * Reads a byte as a single Char. The byte must be valid ASCII (this
   * method is used to parse JSON values like numbers, constants, or
   * delimiters, which are known to be within ASCII).
   */
  protected[this] final def at(i: Int): Char =
    if (i < Bufsize) curr(i).toChar
    else if (i < Allsize) next(i & Mask).toChar
    else { grow(); at(i) }

  /**
   * Access a byte range as a string.
   *
   * Since the underlying data are UTF-8 encoded, i and k must occur
   * on unicode boundaries. Also, the resulting String is not
   * guaranteed to have length (k - i).
   */
  protected[this] final def at(i: Int, k: Int): CharSequence = {
    val len = k - i
    if (k > Allsize) {
      grow()
      at(i, k)
    } else if (k <= Bufsize) {
      new String(curr, i, len, utf8)
    } else if (i >= Bufsize) {
      new String(next, i - Bufsize, len, utf8)
    } else {
      val arr = new Array[Byte](len)
      val mid = Bufsize - i
      System.arraycopy(curr, i, arr, 0, mid)
      System.arraycopy(next, 0, arr, mid, k - Bufsize)
      new String(arr, utf8)
    }
  }

  protected[this] final def atEof(i: Int) =
    if (i < Bufsize) i >= ncurr
    else i >= (nnext + Bufsize)
}
