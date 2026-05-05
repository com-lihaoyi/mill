package mill.testkit

import java.io.{ByteArrayOutputStream, PrintStream}

object ChunkingStreams {
  def makeChunkingStream(
      chunks: collection.mutable.ArrayBuffer[Either[geny.Bytes, geny.Bytes]],
      isStdout: Boolean,
      mergeErrIntoOut: Boolean,
      dest: Option[PrintStream] = None
  ): PrintStream =
    new PrintStream(new ByteArrayOutputStream()) {
      override def write(b: Array[Byte], off: Int, len: Int): Unit = {
        val bytes = java.util.Arrays.copyOfRange(b, off, off + len)
        val chunk = new geny.Bytes(bytes)
        val wrapped = if (isStdout || mergeErrIntoOut) Left(chunk) else Right(chunk)
        chunks.synchronized { chunks += wrapped }
        dest.foreach(_.write(b, off, len))
      }
    }
}
