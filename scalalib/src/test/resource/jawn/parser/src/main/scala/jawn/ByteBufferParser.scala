package jawn

import scala.annotation.{switch, tailrec}
import java.nio.ByteBuffer

/**
 * Basic ByteBuffer parser.
 *
 * This assumes that the provided ByteBuffer is ready to be read. The
 * user is responsible for any necessary flipping/resetting of the
 * ByteBuffer before parsing.
 *
 * The parser makes absolute calls to the ByteBuffer, which will not
 * update its own mutable position fields.
 */
final class ByteBufferParser[J](src: ByteBuffer) extends SyncParser[J] with ByteBasedParser[J] {
  private[this] final val start = src.position
  private[this] final val limit = src.limit - start

  private[this] var lineState = 0
  protected[this] def line(): Int = lineState

  protected[this] final def newline(i: Int) { lineState += 1 }
  protected[this] final def column(i: Int) = i

  protected[this] final def close() { src.position(src.limit) }
  protected[this] final def reset(i: Int): Int = i
  protected[this] final def checkpoint(state: Int, i: Int, stack: List[FContext[J]]) {}
  protected[this] final def byte(i: Int): Byte = src.get(i + start)
  protected[this] final def at(i: Int): Char = src.get(i + start).toChar

  protected[this] final def at(i: Int, k: Int): CharSequence = {
    val len = k - i
    val arr = new Array[Byte](len)
    src.position(i + start)
    src.get(arr, 0, len)
    src.position(start)
    new String(arr, utf8)
  }

  protected[this] final def atEof(i: Int) = i >= limit
}
