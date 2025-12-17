package mill.api

import java.nio.file.Files

/**
 * Misc IO utilities, eventually probably should be pushed upstream into
 * ammonite-ops
 */
@deprecated("IO will be removed in Mill 1.0", "Mill 0.12.17")
object IO extends StreamSupport {

  /**
   * Unpacks the given `src` path into the context specific destination directory.
   * @param src The ZIP file
   * @param dest The relative output folder under the context specific destination directory.
   * @param ctx The target context
   * @return The [[PathRef]] to the unpacked folder.
   */
  @deprecated("IO.unpackZip will be removed in Mill 1.0. Use `os.unzip` instead.", "Mill 0.12.17")
  def unpackZip(
      src: os.Path,
      dest: os.RelPath = os.rel / "unpacked"
  )(implicit
      ctx: Ctx.Dest
  ): PathRef = {

    val byteStream = os.read.inputStream(src)
    val zipStream = new java.util.zip.ZipInputStream(byteStream)
    while ({
      zipStream.getNextEntry match {
        case null => false
        case entry =>
          if (!entry.isDirectory) {
            val entryDest = ctx.dest / dest / os.SubPath(entry.getName)
            os.makeDir.all(entryDest / os.up)
            val fileOut = Files.newOutputStream(entryDest.toNIO)
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
