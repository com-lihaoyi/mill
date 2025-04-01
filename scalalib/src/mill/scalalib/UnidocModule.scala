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

  def unidocCompileClasspath = Task {
    Seq(compile().classes) ++ Task.traverse(moduleDeps)(_.compileClasspath)().flatten
  }

  def unidocSourceFiles = Task {
    allSourceFiles() ++ Task.traverse(moduleDeps)(_.allSourceFiles)().flatten
  }

  def unidocCommon(local: Boolean) = Task.Anon {

    val unidocSourceFiles0 = unidocSourceFiles()

    Task.log.info(s"Staging scaladoc for ${unidocSourceFiles0.length} files")

    // the details of the options and jvmWorker call are significantly
    // different between scala-2 scaladoc and scala-3 scaladoc
    // below is for scala-2 variant
    val options: Seq[String] = Seq(
      "-doc-title",
      "Mill",
      "-d",
      Task.dest.toString,
      "-classpath",
      unidocCompileClasspath().map(_.path).mkString(sys.props("path.separator"))
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

    jvmWorker().worker().docJar(
      scalaVersion(),
      scalaOrganization(),
      scalaDocClasspath(),
      scalacPluginClasspath(),
      options ++ unidocSourceFiles0.map(_.path.toString)
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
