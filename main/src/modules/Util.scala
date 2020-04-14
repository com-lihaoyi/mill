package mill.modules


import coursier.Repository
import mill.api.{PathRef, IO}
import mill.util.Ctx
import mill.api.Loose


object Util {

  private val LongMillProps = new java.util.Properties()

  {
    val millOptionsPath = sys.props("MILL_OPTIONS_PATH")
    if(millOptionsPath != null) 
      LongMillProps.load(new java.io.FileInputStream(millOptionsPath))
  }

  def cleanupScaladoc(v: String) = {
    v.linesIterator.map(
      _.dropWhile(_.isWhitespace)
        .stripPrefix("/**")
        .stripPrefix("*/")
        .stripPrefix("*")
        .stripSuffix("**/")
        .stripSuffix("*/")
        .dropWhile(_.isWhitespace)
    ).toArray
      .dropWhile(_.isEmpty)
      .reverse
      .dropWhile(_.isEmpty)
      .reverse
  }
  def download(url: String, dest: os.RelPath = os.rel / "download")(implicit ctx: Ctx.Dest) = {
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

  def downloadUnpackZip(url: String, dest: os.RelPath = os.rel / "unpacked")
                       (implicit ctx: Ctx.Dest) = {

    val tmpName = if (dest == os.rel / "tmp.zip") "tmp2.zip" else "tmp.zip"
    val downloaded = download(url, os.rel / tmpName)
    IO.unpackZip(downloaded.path, dest)
  }


  def millProjectModule(key: String,
                        artifact: String,
                        repositories: Seq[Repository],
                        resolveFilter: os.Path => Boolean = _ => true,
                        artifactSuffix: String = "_2.13") = {
    val localPath = millProperty(key)
    if (localPath != null) {
      mill.api.Result.Success(
        mill.api.Loose.Agg.from(localPath.split(',').map(p => PathRef(os.Path(p), quick = true)))
      )
    } else {
      mill.modules.Jvm.resolveDependencies(
        repositories,
        Seq(
          coursier.Dependency(
            coursier.Module(coursier.Organization("com.lihaoyi"), coursier.ModuleName(artifact + artifactSuffix)),
            millProperty("MILL_VERSION")
          )
        ),
        Nil
      ).map(_.filter(x => resolveFilter(x.path)))
    }
  }

  def millProperty(key: String): String = {
    val sysPropValue = sys.props(key)
    if(sysPropValue != null) sysPropValue // system property has priority
    else LongMillProps.getProperty(key)
  }
}
