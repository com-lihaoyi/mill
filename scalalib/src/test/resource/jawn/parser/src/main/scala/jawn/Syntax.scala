package jawn

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.charset.Charset
import scala.annotation.{switch, tailrec}
import scala.util.Try

object Syntax {
  implicit def unitFacade: Facade[Unit] = NullFacade

  def checkString(s: String): Boolean =
    Try(new StringParser(s).parse).isSuccess

  def checkPath(path: String): Boolean =
    Try(ChannelParser.fromFile(new File(path)).parse).isSuccess

  def checkFile(file: File): Boolean =
    Try(ChannelParser.fromFile(file).parse).isSuccess

  def checkChannel(ch: ReadableByteChannel): Boolean =
    Try(ChannelParser.fromChannel(ch).parse).isSuccess

  def checkByteBuffer(buf: ByteBuffer): Boolean =
    Try(new ByteBufferParser(buf).parse).isSuccess
}
