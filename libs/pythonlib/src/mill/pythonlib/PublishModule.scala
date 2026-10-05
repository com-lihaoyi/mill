package mill.pythonlib

import mill.javalib.publish.License
import mill.{Command, PathRef, T, Task}

/**
 * A python module which also defines how to build and publish source distributions and wheels.
 */
trait PublishModule extends PythonModule {

  override def moduleDeps: Seq[PublishModule] = super.moduleDeps.map {
    case m: PublishModule => m
    case other =>
      throw Exception(
        s"PublishModule moduleDeps need to be also PublishModules. $other is not a PublishModule"
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
   * SPDX license expression written to the package metadata. Override this
   * when [[publishMeta]].license does not use an SPDX identifier.
   */
  def publishLicenseExpression: T[String] = Task { publishMeta().license.id }

  /**
   * Import package built into the distribution. By default, uv_build derives
   * this from the normalized distribution name. Override this when the name
   * used in `import` statements differs from [[publishMeta]].name.
   */
  def publishModuleName: T[String] = Task {
    PublishModule.normalizeModuleName(publishMeta().name)
  }

  /** Import packages built into the distribution. */
  def publishModuleNames: T[Seq[String]] = Task { Seq(publishModuleName()) }

  /**
   * The content of the PEP-518-compliant `pyproject.toml` file, which describes how to package this
   * module into a distribution (sdist and wheel).
   *
   * By default, Mill will generate this file for you from the information it knows (e.g.
   * dependencies declared in [[pythonDeps]] and metadata from [[publishMeta]]). It will use
   * `uv_build` as the build backend, and `uv build` as the frontend.
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
   *   everything up into an sdist (via `uv build`, although this is an
   *   implementation detail). You can include additional files in this directory via the
   *   [[buildFiles]] task.
   */
  def pyproject: T[String] = Task {
    val moduleNames = Task.traverse(moduleDeps)(_.publishMeta)().map(_.name)
    val moduleVersions = Task.traverse(moduleDeps)(_.publishVersion)()
    val moduleRequires = moduleNames.zip(moduleVersions).map { case (n, v) => s"$n>=$v" }
    val deps = (moduleRequires ++ pythonDeps()).map(PublishModule.tomlString).mkString(", ")
    val moduleNameConfig = publishModuleNames() match {
      case Seq(name) => PublishModule.tomlString(name)
      case names => names.map(PublishModule.tomlString).mkString("[", ", ", "]")
    }
    val meta = publishMeta()

    s"""|[project]
        |name=${PublishModule.tomlString(meta.name)}
        |version=${PublishModule.tomlString(publishVersion())}
        |description=${PublishModule.tomlString(meta.description)}
        |readme=${PublishModule.tomlString(publishReadme().path.last)}
        |dependencies=[${deps}]
        |requires-python=${PublishModule.tomlString(meta.requiresPython)}
        |license=${PublishModule.tomlString(publishLicenseExpression())}
        |keywords=[${meta.keywords.map(PublishModule.tomlString).mkString(",")}]
        |classifiers=[${meta.classifiers.map(PublishModule.tomlString).mkString(",")}]
        |authors=[${meta.authors.map(a =>
         s"{name=${PublishModule.tomlString(a.name)}, email=${PublishModule.tomlString(a.email)}}"
       ).mkString(",")}]
        |
        |[project.urls]
        |${meta.urls.toSeq.sortBy(_._1).map { case (name, url) =>
         s"${PublishModule.tomlString(name)}=${PublishModule.tomlString(url)}"
       }.mkString("\n")}
        |
        |[build-system]
        |requires=["uv_build>=0.12.12,<0.13"]
        |build-backend="uv_build"
        |
        |[tool.uv.build-backend]
        |module-name=$moduleNameConfig
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
        Task.fail(
          s"No readme file found in `${moduleDir}`. A readme file is required for publishing distributions. " +
            s"Please create a file named `${moduleDir}/readme*` (any capitalization), or override the `publishReadme` task."
        )
      case Some(path) =>
        PathRef(path)
    }
  }

  /**
   * Bundle everything up into a source distribution (sdist).
   *
   * @see [[pyproject]]
   */
  def sdist: T[PathRef] = Task {

    // uv_build expects a single source root, so flatten all source directories
    // into one hierarchy.
    val flattenedSrc = Task.dest / "src"
    for (source <- (sources() ++ resources()); if os.exists(source.path)) {
      if (os.isDir(source.path)) {
        for (path <- os.list(source.path)) {
          os.copy.into(path, flattenedSrc, mergeFolders = true, createFolders = true)
        }
      } else {
        val sourcePath =
          PathRef.toResolvedOsPathAnchored(source.path, mill.api.BuildCtx.workspaceRoot)
        val relativePath = sourcePath.relativeTo(mill.api.BuildCtx.workspaceRoot)
        os.copy.over(sourcePath, flattenedSrc / relativePath, createFolders = true)
      }
    }

    // copy over other, non-source files
    os.write(Task.dest / "pyproject.toml", pyproject())
    for ((dest, src) <- buildFiles()) {
      os.copy(src.path, Task.dest / os.SubPath(dest), createFolders = true, replaceExisting = true)
    }

    uvRunner().run(
      (
        "build",
        PublishModule.uvBuildPythonArgs(pythonVersion()),
        uvIndexArgs(),
        "--sdist",
        "--clear",
        "--no-create-gitignore",
        "--out-dir",
        Task.dest / "dist",
        Task.dest
      ),
      workingDir = Task.dest
    )
    val artifacts = os.list(Task.dest / "dist").filter(_.last.endsWith(".tar.gz"))
    artifacts match {
      case Seq(artifact) => PathRef(artifact)
      case _ =>
        Task.fail(s"Expected exactly one source distribution, found: ${artifacts.mkString(", ")}")
    }
  }

  /**
   * Build a binary distribution of this module.
   *
   * @see [[pyproject]]
   */
  def wheel: T[PathRef] = Task {
    uvRunner().run(
      (
        // format: off
        "build",
        PublishModule.uvBuildPythonArgs(pythonVersion()),
        uvIndexArgs(),
        "--wheel",
        "--clear",
        "--no-create-gitignore",
        "--out-dir", Task.dest / "dist",
        sdist().path
        // format: on
      ),
      workingDir = Task.dest
    )
    val artifacts = os.list(Task.dest / "dist").filter(_.ext == "whl")
    artifacts match {
      case Seq(artifact) => PathRef(artifact)
      case _ => Task.fail(s"Expected exactly one wheel, found: ${artifacts.mkString(", ")}")
    }
  }

  /** The repository (index) URL to publish packages to. */
  def publishRepositoryUrl: T[String] = Task { "https://upload.pypi.org/legacy/" }

  /** All artifacts that should be published. */
  def publishArtifacts: T[Seq[PathRef]] = Task {
    Seq(sdist(), wheel())
  }

  /** Environment shared by publish validation and the real upload. */
  private def publishEnv: Task[Map[String, String]] = Task.Anon {
    val uvVariables = Task.env.collect {
      case (key, value) if key.startsWith("UV_PUBLISH_") => key -> value
    }
    val millVariables = Task.env.collect {
      case (key, value) if key.startsWith("MILL_UV_PUBLISH_") =>
        key.drop(5) -> value // MILL_UV_PUBLISH_* -> UV_PUBLISH_*
    }
    Map("UV_PUBLISH_URL" -> publishRepositoryUrl()) ++ uvVariables ++ millVariables
  }

  /** Validate artifacts with a dry-run `uv publish`. */
  def checkPublish(): Command[Unit] = Task.Command {
    uvRunner().run(
      (
        // format: off
        "publish",
        "--dry-run",
        "--trusted-publishing", "never",
        publishArtifacts().map(_.path)
        // format: on
      ),
      env = publishEnv()
    )
  }

  /**
   * Publish the [[sdist]] and [[wheel]] to the package repository (index)
   * defined in this module.
   *
   * You can configure this command with uv's `UV_PUBLISH_*` environment
   * variables or equivalent `MILL_UV_PUBLISH_*` variables. For example:
   *
   * ```
   * MILL_UV_PUBLISH_URL=https://test.pypi.org/legacy/
   * ```
   *
   * @see [[publishRepositoryUrl]]
   */
  def publish(): Command[Unit] = Task.Command {
    uvRunner().run(
      (
        // format: off
        "publish",
        publishArtifacts().map(_.path)
        // format: on
      ),
      env = publishEnv()
    )
  }

}

object PublishModule {
  private[pythonlib] def uvBuildPythonArgs(pythonVersion: String): Seq[String] =
    Seq("--python", pythonVersion)

  private[pythonlib] def normalizeModuleName(name: String): String =
    name.toLowerCase(java.util.Locale.ROOT).replaceAll("[._-]+", "_")

  private[pythonlib] def tomlString(value: String): String = {
    val escaped = value.flatMap {
      case '\b' => "\\b"
      case '\t' => "\\t"
      case '\n' => "\\n"
      case '\f' => "\\f"
      case '\r' => "\\r"
      case '"' => "\\\""
      case '\\' => "\\\\"
      case char if char.isControl => f"\\u${char.toInt}%04x"
      case char => char.toString
    }
    s"\"$escaped\""
  }

  private implicit lazy val licenseFormat: upickle.ReadWriter[License] =
    upickle.macroRW

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
      license: mill.javalib.publish.License,
      authors: Seq[Developer],
      keywords: Seq[String] = Seq(),
      classifiers: Seq[String] = Seq(),
      urls: Map[String, String] = Map()
  )
  object PublishMeta {
    implicit val rw: upickle.ReadWriter[PublishMeta] = upickle.macroRW
  }

  case class Developer(
      name: String,
      email: String
  )
  object Developer {
    implicit val rw: upickle.ReadWriter[Developer] = upickle.macroRW
  }

}
