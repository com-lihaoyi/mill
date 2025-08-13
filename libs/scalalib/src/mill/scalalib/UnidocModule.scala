package mill.scalalib
import mill.*
import mill.api.BuildCtx
import mill.javalib.api.JvmWorkerUtil
import mill.javalib.api.internal.ZincScaladocJar

/**
 * Mix this in to any [[ScalaModule]] to provide a [[unidocSite]] task that
 * can be used to build a unified scaladoc site for this module and all of
 * its transitive dependencies
 */
trait UnidocModule extends ScalaModule {

  /** The URL of the source code of this module. */
  def unidocSourceUrl: T[Option[String]] = None

  /** Passed as `-doc-version` to scaladoc. */
  def unidocVersion: T[Option[String]] = None

  def unidocCompileClasspath: T[Seq[PathRef]] = Task {
    Seq(compile().classes) ++ Task.traverse(moduleDeps)(_.compileClasspath)().flatten
  }

  /**
   * Which module dependencies to include in the scaladoc site.
   *
   * By default, all transitive module dependencies are included.
   */
  def unidocModuleDeps: Seq[JavaModule] = transitiveModuleDeps

  def unidocSourceFiles: T[Seq[PathRef]] = Task {
    if (JvmWorkerUtil.isScala3(scalaVersion())) {
      // On Scala 3 scaladoc only accepts .tasty files and .jar files
      Task.traverse(unidocModuleDeps)(_.compile)().map(_.classes)
        .filter(pr => os.exists(pr.path))
        .flatMap(pr => os.walk(pr.path))
        .filter(path => path.ext == "tasty" || path.ext == "jar")
        .map(PathRef(_))
    } else
      Task.traverse(unidocModuleDeps)(_.allSourceFiles)().flatten
  }

  /** The title of the scaladoc site. */
  def unidocDocumentTitle: T[String]

  /** Extra options passed to scaladoc. */
  def unidocOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * @param local whether to use 'file://' as the `-doc-source-url`/`-source-links`.
   */
  def unidocCommon(local: Boolean): Task[PathRef] = Task.Anon {
    val scalaVersion0 = scalaVersion()
    val onScala3 = JvmWorkerUtil.isScala3(scalaVersion0)

    val scalaOrganization0 = scalaOrganization()
    val scalaDocClasspath0 = scalaDocClasspath()
    val scalacPluginClasspath0 = scalacPluginClasspath()
    val unidocSourceFiles0 = unidocSourceFiles()

    Task.log.info(s"Staging scaladoc for ${unidocSourceFiles0.length} files")

    // the details of the options and jvmWorker call are significantly
    // different between scala-2 scaladoc and scala-3 scaladoc, so make sure to
    // use the correct options for the correct version
    val options: Seq[String] = Seq(
      "-doc-title",
      unidocDocumentTitle(),
      "-d",
      Task.dest.toString,
      "-classpath",
      unidocCompileClasspath().map(_.path).mkString(sys.props("path.separator"))
    ) ++
      unidocVersion().toSeq.flatMap(Seq("-doc-version", _)) ++
      unidocSourceUrl().toSeq.flatMap { url =>
        val sourceLinksOption = if (onScala3) "-source-links" else "-doc-source-url"

        if (local) Seq(
          sourceLinksOption,
          "file://€{FILE_PATH_EXT}"
        )
        else {
          val workspaceRoot = BuildCtx.workspaceRoot
          Seq(
            sourceLinksOption,
            // Relative path to the workspace
            if (onScala3) s"$workspaceRoot=$url€{FILE_PATH_EXT}" else s"$url€{FILE_PATH_EXT}",
            "-sourcepath",
            workspaceRoot.toString
          )
        }
      } ++ unidocOptions()

    Task.log.info(
      s"""|Running Unidoc with: 
          |  scalaVersion: ${scalaVersion0}
          |  scalaOrganization: ${scalaOrganization0}
          |  options: $options
          |  scalaDocClasspath: ${scalaDocClasspath0.map(_.path)}
          |  scalacPluginClasspath: ${scalacPluginClasspath0.map(_.path)}
          |  unidocSourceFiles: ${unidocSourceFiles0.map(_.path)}
          |""".stripMargin
    )

    jvmWorker().internalWorker().scaladocJar(
      ZincScaladocJar(
        scalaVersion(),
        scalaOrganization(),
        scalaDocClasspath(),
        scalacPluginClasspath(),
        options ++ unidocSourceFiles0.map(_.path.toString)
      ),
      javaHome().map(_.path)
    ) match {
      case true => PathRef(Task.dest)
      case false => Task.fail("unidoc generation failed")
    }
  }

  def unidocLocal: T[PathRef] = Task {
    unidocCommon(true)()
  }

  def unidocSite: T[PathRef] = Task {
    val raw = unidocCommon(false)().path
    val dest = if (!raw.startsWith(Task.dest)) {
      // dest is outside, so we copy it, as we want to modify it
      os.copy.over(raw, Task.dest)
      Task.dest
    } else {
      raw
    }
    val replacePrefix = s"file://${BuildCtx.workspaceRoot}"
    for {
      sourceUrl <- unidocSourceUrl()
      p <- os.walk(dest) if p.ext == "scala"
    } {
      os.write(p, os.read(p).replace(replacePrefix, sourceUrl))
    }
    PathRef(dest)
  }
}
