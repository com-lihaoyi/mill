package mill.pythonlib

import mill.api.Result
import mill.scalalib.publish.License
import mill.{Command, PathRef, T, Task}

/**
 * A python module which also defines how to build and publish source distributions and wheels.
 */
trait PublishModule extends PythonModule {

  override def moduleDeps: Seq[PublishModule] = super.moduleDeps.map {
    case m: PublishModule => m
    case other =>
      throw new Exception(
        s"PublishModule moduleDeps need to be also PublishModules. $other is not a PublishModule"
      )
  }

  override def pythonToolDeps = Task {
    super.pythonToolDeps() ++ Seq(
      "setuptools>=75.6.0",
      "build>=1.2.2",
      "twine>=5.1.1"
    )
  }

  /**
   * Metadata about your project, required to build and publish.
   *
   * This is roughly equivalent to what you'd find in the general section of a `pyproject.toml` file
   * https://packaging.python.org/en/latest/guides/writing-pyproject-toml/#about-your-project.
   */
  def publishMeta: T[PublishModule.PublishMeta]

  /**
   * The artifact version that this module would be published as.
   */
  def publishVersion: T[String]

  /**
   * The content of the PEP-518-compliant `pyproject.toml` file, which describes how to package this
   * module into a distribution (sdist and wheel).
   *
   * By default, Mill will generate this file for you from the information it knows (e.g.
   * dependencies declared in [[pythonDeps]] and metadata from [[publishMeta]]). It will use
   * `setuptools` as the build backend, and `build` as the frontend.
   *
   * You can however override this task to read your own `pyproject.toml` file, if you need to. In
   * this case, please note the following:
   *
   * - Mill will create a source distribution first, and then use that to build a binary
   *   distribution (aka wheel). Going through this intermediary step, rather than building a wheel
   *   directly, ensures that end users can rebuild wheels on their systems, for example if a
   *   platform-dependent wheel is not available pre-made.
   *
   * - Hence, the source distribution will need to be "self contained". In particular this means
   *   that you can't reference files by absolute path within it.
   *
   * - Mill creates a "staging" directory in the [[sdist]] task, which will be used to bundle
   *   everything up into an sdist (via the `build` python command, although this is an
   *   implementation detail). You can include additional files in this directory via the
   *   [[buildFiles]] task.
   */
  def pyproject: T[String] = Task {
    val moduleNames = Task.traverse(moduleDeps)(_.publishMeta)().map(_.name)
    val moduleVersions = Task.traverse(moduleDeps)(_.publishVersion)()
    val moduleRequires = moduleNames.zip(moduleVersions).map { case (n, v) => s"$n>=$v" }
    val deps = (moduleRequires ++ pythonDeps()).map(s => s"\"$s\"").mkString(", ")

    s"""|[project]
        |name="${publishMeta().name}"
        |version="${publishVersion()}"
        |description="${publishMeta().description}"
        |readme="${publishReadme().path.last}"
        |dependencies=[${deps}]
        |requires-python="${publishMeta().requiresPython}"
        |license={text="${publishMeta().license.id}"}
        |keywords=[${publishMeta().keywords.map(s => s"\"$s\"").mkString(",")}]
        |classifiers=[${publishMeta().classifiers.map(s => s"\"$s\"").mkString(",")}]
        |authors=[${publishMeta().authors.map(a =>
         s"{name=\"${a.name}\", email=\"${a.email}\"}"
       ).mkString(",")}]
        |
        |[project.urls]
        |${publishMeta().urls.map(u => s"\"${u._1}\"=\"${u._2}\"").mkString("\n")}
        |
        |[build-system]
        |requires=["setuptools"]
        |build-backend="setuptools.build_meta"
        |""".stripMargin
  }

  /**
   * Files to be included in the directory used during the packaging process, apart from
   * [[pyproject]].
   *
   * The format is `<destination path> -> <source path>`. Where `<destination path>` is relative to
   * some build directory, where you'll also find `src` and `pyproject.toml`.
   *
   * @see [[pyproject]]
   */
  def buildFiles: T[Map[String, PathRef]] = Task {
    Map(
      publishReadme().path.last -> publishReadme()
    )
  }

  /**
   * The readme file to include in the published distribution.
   */
  def publishReadme: T[PathRef] = Task.Input {
    val readme = if (os.exists(moduleDir)) {
      os.list(moduleDir).find(_.last.toLowerCase().startsWith("readme"))
    } else None
    readme match {
      case None =>
        Result.Failure(
          s"No readme file found in `${moduleDir}`. A readme file is required for publishing distributions. " +
            s"Please create a file named `${moduleDir}/readme*` (any capitalization), or override the `publishReadme` task."
        )
      case Some(path) =>
        Result.Success(PathRef(path))
    }
  }

  /**
   * Bundle everything up into a source distribution (sdist).
   *
   * @see [[pyproject]]
   */
  def sdist: T[PathRef] = Task {

    // we use setup tools by default, which can only work with a single source directory, hence we
    // flatten all source directories into a single hierarchy
    val flattenedSrc = Task.dest / "src"
    for (dir <- (sources() ++ resources()); if os.exists(dir.path)) {
      for (path <- os.list(dir.path)) {
        os.copy.into(path, flattenedSrc, mergeFolders = true, createFolders = true)
      }
    }

    // copy over other, non-source files
    os.write(Task.dest / "pyproject.toml", pyproject())
    for ((dest, src) <- buildFiles()) {
      os.copy(src.path, Task.dest / os.SubPath(dest), createFolders = true, replaceExisting = true)
    }

    // we already do the isolation with mill
    runner().run(("-m", "build", "--no-isolation", "--sdist"), workingDir = Task.dest)
    PathRef(os.list(Task.dest / "dist").head)
  }

  /**
   * Build a binary distribution of this module.
   *
   * @see [[pyproject]]
   */
  def wheel: T[PathRef] = Task {
    val buildDir = Task.dest / "extracted"

    os.makeDir(buildDir)
    os.call(
      ("tar", "xf", sdist().path, "-C", buildDir),
      cwd = Task.dest
    )
    runner().run(
      (
        // format: off
        "-m", "build",
        "--no-isolation", // we already do the isolation with mill
        "--wheel",
        "--outdir", Task.dest / "dist"
        // format: on
      ),
      workingDir = os.list(buildDir).head // sdist archive contains a directory
    )
    PathRef(os.list(Task.dest / "dist").head)
  }

  /** The repository (index) URL to publish packages to. */
  def publishRepositoryUrl: T[String] = Task { "https://upload.pypi.org/" }

  /** All artifacts that should be published. */
  def publishArtifacts: T[Seq[PathRef]] = Task {
    Seq(sdist(), wheel())
  }

  /** Run `twine check` to catch some common packaging errors. */
  def checkPublish(): Command[Unit] = Task.Command {
    runner().run(
      (
        // format: off
        "-m", "twine",
        "check",
        publishArtifacts().map(_.path)
        // format: on
      )
    )
  }

  /**
   * Publish the [[sdist]] and [[wheel]] to the package repository (index)
   * defined in this module.
   *
   * You can configure this command by setting any environment variables
   * understood by `twine`, prefixed with `MILL_`. For example, to change the
   * repository URL:
   *
   * ```
   * MILL_TWINE_REPOSITORY_URL=https://test.pypi.org/legacy/
   * ```
   *
   * @see [[publishRepositoryUrl]]
   */
  def publish(): Command[Unit] = Task.Command {
    val env: Map[String, String] =
      Map(
        "TWINE_REPOSITORY_URL" -> publishRepositoryUrl()
      ) ++
        Task.env ++
        Task.env.collect {
          case (key, value) if key.startsWith("MILL_TWINE_") =>
            key.drop(5) -> value // MILL_TWINE_* -> TWINE_*
          case (key, value) => key -> value
        }

    runner().run(
      (
        // format: off
        "-m", "twine",
        "upload",
        "--non-interactive",
        publishArtifacts().map(_.path)
        // format: on
      ),
      env = env
    )
  }

}

object PublishModule {
  private implicit lazy val licenseFormat: upickle.default.ReadWriter[License] =
    upickle.default.macroRW

  /**
   * Static metadata about a project.
   *
   * This is roughly equivalent to what you'd find in the general section of a `pyproject.toml` file
   * https://packaging.python.org/en/latest/guides/writing-pyproject-toml/#about-your-project.
   */
  case class PublishMeta(
      name: String,
      description: String,
      requiresPython: String,
      license: mill.scalalib.publish.License,
      authors: Seq[Developer],
      keywords: Seq[String] = Seq(),
      classifiers: Seq[String] = Seq(),
      urls: Map[String, String] = Map()
  )
  object PublishMeta {
    implicit val rw: upickle.default.ReadWriter[PublishMeta] = upickle.default.macroRW
  }

  case class Developer(
      name: String,
      email: String
  )
  object Developer {
    implicit val rw: upickle.default.ReadWriter[Developer] = upickle.default.macroRW
  }

}
