package mill.scalalib
import mill.*
import mill.api.BuildCtx
import mill.javalib.api.JvmWorkerUtil
import mill.javalib.api.internal.ZincOp

/**
 * Mix this in to any [[ScalaModule]] to provide a [[unidocSite]] task that
 * can be used to build a unified scaladoc site for this module and all of
 * its transitive dependencies
 */
trait UnidocModule extends ScalaModule {

  /** The URL of the source code of this module. */
  def unidocSourceUrl: T[Option[String]] = Option.empty

  /** Passed as `-doc-version` to scaladoc. */
  def unidocVersion: T[Option[String]] = Option.empty

  def unidocCompileClasspath: T[Seq[PathRef]] = Task {
    Seq(
      compile().classes
    ) ++ Task.traverse(transitiveModuleCompileModuleDeps)(_.compileClasspath)().flatten
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
        val workspaceRoot = BuildCtx.workspaceRoot
        if (onScala3) {
          // Scala 3 records source paths in TASTY relative to the compile-time `-sourceroot` (the
          // workspace, for a reproducible `out/`); give scaladoc the same root so `€{FILE_PATH_EXT}`
          // is the workspace-relative source path, then build the link from it: an absolute
          // `file://` URL for local docs, or the remote base URL for a published site.
          Seq(
            s"-sourceroot:$workspaceRoot",
            "-source-links",
            if (local) s"file://$workspaceRoot€{FILE_PATH_EXT}" else s"$url€{FILE_PATH_EXT}"
          )
        } else if (local) {
          Seq("-doc-source-url", "file://€{FILE_PATH_EXT}")
        } else {
          Seq("-doc-source-url", s"$url€{FILE_PATH_EXT}", "-sourcepath", workspaceRoot.toString)
        }
      } ++ unidocOptions()

    Task.log.info(
      s"""|Running Unidoc with:
          |  scalaVersion: ${scalaVersion0}
          |  scalaOrganization: ${JvmWorkerUtil.scalaOrganization(scalaVersion0)}
          |  options: $options
          |  scalaDocClasspath: ${scalaDocClasspath0.map(_.path)}
          |  scalacPluginClasspath: ${scalacPluginClasspath0.map(_.path)}
          |  unidocSourceFiles: ${unidocSourceFiles0.map(_.path)}
          |""".stripMargin
    )

    val worker = jvmWorker().internalWorker()
    worker.apply(
      ZincOp.ScaladocJar(
        scalaVersion(),
        JvmWorkerUtil.scalaOrganization(scalaVersion()),
        scalaDocClasspath(),
        scalacPluginClasspath(),
        scalaCompilerBridge(),
        options ++ unidocSourceFiles0.map(_.path.toString),
        workDir = Task.dest
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
