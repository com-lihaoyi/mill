package mill.modules


import coursier.Repository
import mill.eval.PathRef
import mill.util.{Ctx, IO, Loose}

object Util {
  def cleanupScaladoc(v: String) = {
    v.linesIterator.map(
      _.dropWhile(_.isWhitespace)
        .stripPrefix("/**")
        .stripPrefix("*/")
        .stripPrefix("*")
        .dropWhile(_.isWhitespace)
    ).toArray
      .dropWhile(_.isEmpty)
      .reverse
      .dropWhile(_.isEmpty)
      .reverse
  }
  def download(url: String, dest: os.RelPath = "download")(implicit ctx: Ctx.Dest) = {
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

  def downloadUnpackZip(url: String, dest: os.RelPath = "unpacked")
                       (implicit ctx: Ctx.Dest) = {

    val tmpName = if (dest == os.rel / "tmp.zip") "tmp2.zip" else "tmp.zip"
    val downloaded = download(url, tmpName)
    unpackZip(downloaded.path, dest)
  }

  def unpackZip(src: os.Path, dest: os.RelPath = "unpacked")
               (implicit ctx: Ctx.Dest) = {

    val byteStream = os.read.inputStream(src)
    val zipStream = new java.util.zip.ZipInputStream(byteStream)
    while({
      zipStream.getNextEntry match{
        case null => false
        case entry =>
          if (!entry.isDirectory) {
            val entryDest = ctx.dest / dest / os.RelPath(entry.getName)
            os.makeDir.all(entryDest / ammonite.ops.up)
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
                        resolveFilter: os.Path => Boolean = _ => true,
                        artifactSuffix: String = "_2.12") = {
    val localPath = sys.props(key)
    if (localPath != null) {
      mill.eval.Result.Success(
        Loose.Agg.from(localPath.split(',').map(p => PathRef(os.Path(p), quick = true)))
      )
    } else {
      mill.modules.Jvm.resolveDependencies(
        repositories,
        Seq(
          coursier.Dependency(
            coursier.Module("com.lihaoyi", artifact + artifactSuffix),
            sys.props("MILL_VERSION")
          )
        ),
        Nil
      ).map(_.filter(x => resolveFilter(x.path)))
    }
  }
}
