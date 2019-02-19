package mill.api

import java.io.{InputStream, OutputStream}

/**
 * Misc IO utilities, eventually probably should be pushed upstream into
 * ammonite-ops
 */
object IO {

  /**
    * Pump the data from the `src` stream into the `dest` stream.
    */
  def stream(src: InputStream, dest: OutputStream): Unit = {
    val buffer = new Array[Byte](4096)
    while ({
      src.read(buffer) match {
        case -1 => false
        case n =>
          dest.write(buffer, 0, n)
          true
      }
    }) ()
  }

  /**
   * Unpacks the given `src` path into the context specific destination directory.
   * @param src The ZIP file
   * @param dest The relative ouput folder under the context specifix destination directory.
   * @param ctx The target context
   * @return The [[PathRef]] to the unpacked folder.
   */
  def unpackZip(src: os.Path, dest: os.RelPath = "unpacked")(implicit ctx: Ctx.Dest): PathRef = {

    val byteStream = os.read.inputStream(src)
    val zipStream = new java.util.zip.ZipInputStream(byteStream)
    while ({
      zipStream.getNextEntry match {
        case null => false
        case entry =>
          if (!entry.isDirectory) {
            val entryDest = ctx.dest / dest / os.RelPath(entry.getName)
            os.makeDir.all(entryDest / os.up)
            val fileOut = new java.io.FileOutputStream(entryDest.toString)
            IO.stream(zipStream, fileOut)
            fileOut.close()
          }
          zipStream.closeEntry()
          true
      }
    }) ()
    PathRef(ctx.dest / dest)
  }
}

import java.io.ByteArrayInputStream

/**
 * A dummy input stream containing an empty byte array.
 */
object DummyInputStream extends ByteArrayInputStream(Array())

/**
 * A dummy output stream that does nothing with what it consumes (think of it as `/dev/null`).
 */
object DummyOutputStream extends java.io.OutputStream {
  override def write(b: Int): Unit = ()
  override def write(b: Array[Byte]): Unit = ()
  override def write(b: Array[Byte], off: Int, len: Int): Unit = ()
}
