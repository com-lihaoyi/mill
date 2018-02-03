package mill.modules


import ammonite.ops.{Path, RelPath, empty, mkdir, read}
import mill.eval.PathRef
import mill.util.Ctx

object Util {
  def download(url: String, dest: RelPath = "download")(implicit ctx: Ctx.DestCtx) = {
    ammonite.ops.mkdir(ctx.dest)
    val out = ctx.dest / dest

    val website = new java.net.URI(url).toURL
    val rbc = java.nio.channels.Channels.newChannel(website.openStream)
    try{
      val fos = new java.io.FileOutputStream(out.toIO)
      try{
        fos.getChannel.transferFrom(rbc, 0, java.lang.Long.MAX_VALUE)
        PathRef(out)
      } finally{
        fos.close()
      }
    } finally{
      rbc.close()
    }
  }

  def downloadUnpackZip(url: String, dest: RelPath = "unpacked")
                       (implicit ctx: Ctx.DestCtx) = {
    ctx.dest
    mkdir(ctx.dest)

    val tmpName = if (dest == empty / "tmp.zip") "tmp2.zip" else "tmp.zip"
    val downloaded = download(url, tmpName)
    unpackZip(downloaded.path, dest)
  }

  def unpackZip(src: Path, dest: RelPath = "unpacked")
               (implicit ctx: Ctx.DestCtx) = {
    mkdir(ctx.dest)

    val byteStream = read.getInputStream(src)
    val zipStream = new java.util.zip.ZipInputStream(byteStream)
    while({
      zipStream.getNextEntry match{
        case null => false
        case entry =>
          if (!entry.isDirectory) {
            val entryDest = ctx.dest / dest / RelPath(entry.getName)
            mkdir(entryDest / ammonite.ops.up)
            val fileOut = new java.io.FileOutputStream(entryDest.toString)
            val buffer = new Array[Byte](4096)
            while ( {
              zipStream.read(buffer) match {
                case -1 => false
                case n =>
                  fileOut.write(buffer, 0, n)
                  true
              }
            }) ()
            fileOut.close()
          }
          zipStream.closeEntry()
          true
      }
    })()
    PathRef(ctx.dest / dest)
  }
}
