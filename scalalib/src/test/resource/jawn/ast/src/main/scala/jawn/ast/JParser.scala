package jawn
package ast

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import scala.util.Try

object JParser {
  implicit val facade = JawnFacade

  def parseUnsafe(s: String): JValue =
    new StringParser(s).parse()

  def parseFromString(s: String): Try[JValue] =
    Try(new StringParser[JValue](s).parse)

  def parseFromCharSequence(cs: CharSequence): Try[JValue] =
    Try(new CharSequenceParser[JValue](cs).parse)

  def parseFromPath(path: String): Try[JValue] =
    parseFromFile(new File(path))

  def parseFromFile(file: File): Try[JValue] =
    Try(ChannelParser.fromFile[JValue](file).parse)

  def parseFromChannel(ch: ReadableByteChannel): Try[JValue] =
    Try(ChannelParser.fromChannel(ch).parse)

  def parseFromByteBuffer(buf: ByteBuffer): Try[JValue] =
    Try(new ByteBufferParser[JValue](buf).parse)

  def async(mode: AsyncParser.Mode): AsyncParser[JValue] =
    AsyncParser(mode)
}
