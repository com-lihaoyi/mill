package mill.util

import java.io.{InputStream, OutputStream}

import scala.tools.nsc.interpreter.OutputStream

/**
  * Misc IO utilities, eventually probably should be pushed upstream into
  * ammonite-ops
  */
object IO {
  def stream(src: InputStream, dest: OutputStream) = {
    val buffer = new Array[Byte](4096)
    while ( {
      src.read(buffer) match {
        case -1 => false
        case n =>
          dest.write(buffer, 0, n)
          true
      }
    }) ()
  }
}

import java.io.{ByteArrayInputStream, OutputStream}

object DummyInputStream extends ByteArrayInputStream(Array())
object DummyOutputStream extends OutputStream{
  override def write(b: Int) = ()
  override def write(b: Array[Byte]) = ()
  override def write(b: Array[Byte], off: Int, len: Int) = ()
}
