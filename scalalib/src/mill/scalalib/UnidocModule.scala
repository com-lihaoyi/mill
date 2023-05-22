package mill.scalalib
import mill._

/**
 * Mix this in to any [[ScalaModule]] to provide a [[unidocSite]] target that
 * can be used to build a unified scaladoc site for this module and all of
 * its transitive dependencies
 */
trait UnidocModule extends ScalaModule {
  def unidocSourceUrl: T[Option[String]] = None

  def unidocVersion: T[Option[String]] = None

  def unidocLocal = T {
    def unidocCompileClasspath =
      Seq(compile().classes) ++ T.traverse(moduleDeps)(_.compileClasspath)().flatten

    val unidocSourceFiles =
      allSourceFiles() ++ T.traverse(moduleDeps)(_.allSourceFiles)().flatten

    T.log.info(s"Staging scaladoc for ${unidocSourceFiles.length} files")

    // the details of the options and zincWorker call are significantly
    // different between scala-2 scaladoc and scala-3 scaladoc
    // below is for scala-2 variant
    val options: Seq[String] = Seq(
      "-doc-title",
      "Mill",
      "-d",
      T.dest.toString,
      "-classpath",
      unidocCompileClasspath.map(_.path).mkString(sys.props("path.separator"))
    ) ++
      unidocVersion().toSeq.flatMap(Seq("-doc-version", _)) ++
      unidocSourceUrl().toSeq.flatMap(_ => Seq("-doc-source-url", "file://€{FILE_PATH}.scala"))

    zincWorker().worker().docJar(
      scalaVersion(),
      scalaOrganization(),
      scalaDocClasspath(),
      scalacPluginClasspath(),
      options ++ unidocSourceFiles.map(_.path.toString)
    ) match {
      case true => mill.api.Result.Success(PathRef(T.dest))
      case false => mill.api.Result.Failure("unidoc generation failed")
    }
  }

  def unidocSite = T {
    os.copy(unidocLocal().path, T.dest, mergeFolders = true)
    for {
      sourceUrl <- unidocSourceUrl()
      p <- os.walk(T.dest) if p.ext == "scala"
    } {
      os.write(p, os.read(p).replace(s"file://${T.workspace}", sourceUrl))
    }
    PathRef(T.dest)
  }
}
