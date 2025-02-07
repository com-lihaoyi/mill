package mill.util

import java.nio.file.Files
import java.nio.file.Paths
import coursier.Repository
import mill.api.Loose.Agg
import mill.api.{BuildInfo, Ctx, PathRef, Result}

object Util {

  def isInteractive(): Boolean = mill.main.client.Util.hasConsole()

  val newLine: String = System.lineSeparator()

  val windowsPlatform: Boolean = System.getProperty("os.name").startsWith("Windows")


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

  /**
   * Deprecated helper method, intended to allow runtime resolution and in-development-tree testings of mill plugins possible.
   * This design has issues and will probably be replaced.
   */
  def millProjectModule(
      artifact: String,
      repositories: Seq[Repository],
      // this should correspond to the mill runtime Scala version
      artifactSuffix: String = "_3"
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
      force = Nil
    ).map(_.map(_.withRevalidateOnce))
  }

  def millProperty(key: String): Option[String] =
    Option(sys.props(key)) // System property has priority
      .orElse(Option(LongMillProps.getProperty(key)))

  def leftPad(s: String, targetLength: Int, char: Char): String = {
    char.toString * (targetLength - s.length) + s
  }
}
