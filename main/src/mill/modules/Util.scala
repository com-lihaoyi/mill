package mill.modules


import ammonite.ops.{Path, RelPath, empty, mkdir, read}
import coursier.Repository
import mill.eval.PathRef
import mill.util.{Ctx, IO, Loose}

object Util {
  def download(url: String, dest: RelPath = "download")(implicit ctx: Ctx.Dest) = {
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
                       (implicit ctx: Ctx.Dest) = {

    val tmpName = if (dest == empty / "tmp.zip") "tmp2.zip" else "tmp.zip"
    val downloaded = download(url, tmpName)
    unpackZip(downloaded.path, dest)
  }

  def unpackZip(src: Path, dest: RelPath = "unpacked")
               (implicit ctx: Ctx.Dest) = {

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
            IO.stream(zipStream, fileOut)
            fileOut.close()
          }
          zipStream.closeEntry()
          true
      }
    })()
    PathRef(ctx.dest / dest)
  }

  def millProjectModule(key: String,
                        artifact: String,
                        repositories: Seq[Repository],
                        resolveFilter: Path => Boolean = _ => true) = {
    val localPath = sys.props(key)
    if (localPath != null) {
      mill.eval.Result.Success(
        Loose.Agg.from(localPath.split(',').map(p => PathRef(Path(p), quick = true)))
      )
    } else {
      mill.modules.Jvm.resolveDependencies(
        repositories,
        Seq(
          coursier.Dependency(
            coursier.Module("com.lihaoyi", artifact + "_2.12"),
            sys.props("MILL_VERSION")
          )
        ),
        Nil
      ).map(_.filter(x => resolveFilter(x.path)))
    }
  }
}
