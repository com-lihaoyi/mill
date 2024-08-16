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

  def unidocCommon(local: Boolean) = Task.anon {
    def unidocCompileClasspath =
      Seq(compile().classes) ++ Task.traverse(moduleDeps)(_.compileClasspath)().flatten

    val unidocSourceFiles =
      allSourceFiles() ++ Task.traverse(moduleDeps)(_.allSourceFiles)().flatten

    Task.log.info(s"Staging scaladoc for ${unidocSourceFiles.length} files")

    // the details of the options and zincWorker call are significantly
    // different between scala-2 scaladoc and scala-3 scaladoc
    // below is for scala-2 variant
    val options: Seq[String] = Seq(
      "-doc-title",
      "Mill",
      "-d",
      Task.dest.toString,
      "-classpath",
      unidocCompileClasspath.map(_.path).mkString(sys.props("path.separator"))
    ) ++
      unidocVersion().toSeq.flatMap(Seq("-doc-version", _)) ++
      unidocSourceUrl().toSeq.flatMap { url =>
        if (local) Seq(
          "-doc-source-url",
          "file://€{FILE_PATH}.scala"
        )
        else Seq(
          "-doc-source-url",
          url + "€{FILE_PATH}.scala",
          "-sourcepath",
          Task.workspace.toString
        )
      }

    zincWorker().worker().docJar(
      scalaVersion(),
      scalaOrganization(),
      scalaDocClasspath(),
      scalacPluginClasspath(),
      options ++ unidocSourceFiles.map(_.path.toString)
    ) match {
      case true => mill.api.Result.Success(PathRef(Task.dest))
      case false => mill.api.Result.Failure("unidoc generation failed")
    }
  }

  def unidocLocal = Task {
    unidocCommon(true)()
    PathRef(Task.dest)
  }

  def unidocSite = Task {
    unidocCommon(false)()
    for {
      sourceUrl <- unidocSourceUrl()
      p <- os.walk(Task.dest) if p.ext == "scala"
    } {
      os.write(p, os.read(p).replace(s"file://${Task.workspace}", sourceUrl))
    }
    PathRef(Task.dest)
  }
}
