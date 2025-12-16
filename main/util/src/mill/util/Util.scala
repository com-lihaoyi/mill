package mill.util

import java.nio.file.Files
import java.nio.file.Paths
import coursier.Repository
import mill.api.Loose.Agg
import mill.api.{BuildInfo, Ctx, IO, PathRef, Result}

@deprecated("Util will be removed in Mill 1.0", "Mill 0.12.17")
object Util {

  def isInteractive(): Boolean = mill.main.client.Util.hasConsole()

  val newLine: String = System.lineSeparator()

  val windowsPlatform: Boolean = System.getProperty("os.name").startsWith("Windows")

  val java9OrAbove: Boolean = !System.getProperty("java.specification.version").startsWith("1.")

  private val LongMillProps = new java.util.Properties()

  {
    val millOptionsPath = sys.props("MILL_OPTIONS_PATH")
    if (millOptionsPath != null)
      LongMillProps.load(Files.newInputStream(Paths.get(millOptionsPath)))
  }

  def cleanupScaladoc(v: String): Array[String] = {
    v.linesIterator.map(
      _.dropWhile(_.isWhitespace)
        .stripPrefix("/**")
        .stripPrefix("*/")
        .stripPrefix("*")
        .stripSuffix("**/")
        .stripSuffix("*/")
        .dropWhile(_.isWhitespace)
        .reverse
        .dropWhile(_.isWhitespace)
        .reverse
    ).toArray
      .dropWhile(_.isEmpty)
      .reverse
      .dropWhile(_.isEmpty)
      .reverse
  }

  @deprecated("Util.download will be removed in Mill 1.0. Use `os.write` with `requests.get.strean` instead.", "Mill 0.12.17")
  def download(url: String, dest: os.RelPath = os.rel / "download")(implicit
      ctx: Ctx.Dest
  ): PathRef = {
    val out = ctx.dest / dest
    val website = new java.net.URI(url).toURL
    val websiteInputStream = website.openStream
    try {
      Files.copy(websiteInputStream, out.toNIO)
      PathRef(out)
    } finally {
      websiteInputStream.close()
    }
  }

  @deprecated("Util.downloadUnpackZip will be removed in Mill 1.0. Use `os.unzip.stream` with `requests.get.strean` and `os.unzip` instead.", "Mill 0.12.17")
  def downloadUnpackZip(url: String, dest: os.RelPath = os.rel / "unpacked")(implicit
      ctx: Ctx.Dest
  ): PathRef = {

    val tmpName = if (dest == os.rel / "tmp.zip") "tmp2.zip" else "tmp.zip"
    val downloaded = download(url, os.rel / tmpName)
    IO.unpackZip(downloaded.path, dest)
  }

  /**
   * Deprecated helper method, intended to allow runtime resolution and in-development-tree testings of mill plugins possible.
   * This design has issues and will probably be replaced.
   */
  def millProjectModule(
      artifact: String,
      repositories: Seq[Repository],
      @deprecated(
        "This parameter is now ignored, use exclusions instead or mark some dependencies as provided when you publish modules",
        "Mill after 0.12.5"
      )
      deprecatedResolveFilter: os.Path => Boolean = _ => true,
      // this should correspond to the mill runtime Scala version
      artifactSuffix: String = "_2.13"
  ): Result[Agg[PathRef]] = {

    mill.util.Jvm.resolveDependencies(
      repositories = repositories,
      deps = Seq(
        coursier.Dependency(
          coursier.Module(
            coursier.Organization("com.lihaoyi"),
            coursier.ModuleName(artifact + artifactSuffix)
          ),
          BuildInfo.millVersion
        )
      ),
      force = Nil,
      deprecatedResolveFilter = deprecatedResolveFilter
    ).map(_.map(_.withRevalidateOnce))
  }

  def millProperty(key: String): Option[String] =
    Option(sys.props(key)) // System property has priority
      .orElse(Option(LongMillProps.getProperty(key)))

  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }

  def renderSecondsSuffix(millis: Long): String = (millis / 1000).toInt match {
    case 0 => ""
    case n => s" ${n}s"
  }
}
