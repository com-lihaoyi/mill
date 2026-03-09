package mill.kotlinlib.ktlint

import mainargs.arg
import mill.*
import mill.api.PathRef
import mill.api.{Discover, ExternalModule}
import mill.javalib.{JavaModule, Lib}
import mill.kotlinlib.{DepSyntax, KotlinModule}
import mill.util.Tasks
import mill.util.Jvm
import mill.api.BuildCtx

/**
 * Performs formatting checks on Kotlin source files using [[https://pinterest.github.io/ktlint/latest/install/integrations/ Ktlint]].
 */
trait KtlintModule extends JavaModule {

  /**
   * Runs [[https://pinterest.github.io/ktlint/latest/install/integrations/ Ktlint]]
   */
  def ktlint(@mainargs.arg ktlintArgs: KtlintArgs): Command[Unit] = Task.Command {
    KtlintModule.ktlintAction(
      ktlintArgs,
      sources(),
      ktlintConfig(),
      ktlintOptions(),
      ktlintClasspath()
    )
  }

  /**
   * Classpath for running Ktlint.
   */
  def ktlintClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      Seq(mvn"com.pinterest.ktlint:ktlint-cli:${ktlintVersion()}"),
      resolutionParamsMapOpt = Some(KotlinModule.addJvmVariantAttributes)
    )
  }

  def ktlintConfig0 = Task.Source(BuildCtx.workspaceRoot / ".editorconfig")

  /**
   * Ktlint configuration file.
   */
  def ktlintConfig: T[Option[PathRef]] = Task { Some(ktlintConfig0()) }

  /**
   * Ktlint version.
   */
  def ktlintVersion: T[String] = Task {
    "1.4.1"
  }

  /**
   * Additional arguments for Ktlint. Check [[https://pinterest.github.io/ktlint/latest/install/cli/ available options]].
   */
  def ktlintOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }
}

object KtlintModule extends ExternalModule with KtlintModule with DefaultTaskModule {
  override def defaultTask(): String = "reformatAll"

  lazy val millDiscover = Discover[this.type]

  /**
   * Reformats Kotlin source files.
   */
  def reformatAll(
      @arg(positional = true) sources: Tasks[Seq[PathRef]] =
        Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {
    ktlintAction(
      KtlintArgs(format = true, check = true),
      Task.sequence(sources.value)().flatten,
      ktlintConfig(),
      ktlintOptions(),
      ktlintClasspath()
    )
  }

  /**
   * Checks the Kotlin source files formatting without reformatting the files.
   */
  def checkFormatAll(
      @arg(positional = true) sources: Tasks[Seq[PathRef]] =
        Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {
    ktlintAction(
      KtlintArgs(format = false, check = true),
      Task.sequence(sources.value)().flatten,
      ktlintConfig(),
      ktlintOptions(),
      ktlintClasspath()
    )
  }

  private def ktlintAction(
      ktlintArgs: KtlintArgs,
      pathsToFormat: Seq[PathRef],
      config: Option[PathRef],
      options: Seq[String],
      classPath: Seq[PathRef]
  )(using ctx: mill.api.TaskCtx): Unit = {
    val sourceFiles = Lib.findSourceFiles(pathsToFormat, Seq("kt", "kts"))
      // skip formatting single-file projects since Palantir Format messes up the header block
      .filter(!os.read(_).startsWith("//|"))
    if (sourceFiles.isEmpty) {
      // no files found
      if (pathsToFormat.isEmpty) Task.fail("No paths selected.")
      else {
        // The sources simple didn't contain any formattable source file,
        // which is probably ok for a freshly set up project
        // we just return, since ktlint defaults to format the current working dir
        ctx.log.info(s"No kotlin sources found.")
        return
      }
    }

    if (ktlintArgs.check) {
      ctx.log.info(s"Checking format in ${sourceFiles.size} kotlin sources ...")
    } else {
      ctx.log.info(s"Formatting ${sourceFiles.size} kotlin sources ...")
    }

    val configArgument = config match {
      case Some(path) => Seq("--editorconfig", path.path.toString())
      case None => Seq.empty
    }
    val formatArgument = if (ktlintArgs.format) Seq("--format") else Seq.empty

    val args = Seq.newBuilder[String]
    args ++= options
    args ++= configArgument
    args ++= formatArgument
    args ++= sourceFiles.map(_.toString())

    val exitCode = BuildCtx.withFilesystemCheckerDisabled {
      Jvm.callProcess(
        mainClass = "com.pinterest.ktlint.Main",
        classPath = classPath.map(_.path).toVector,
        mainArgs = args.result(),
        cwd = moduleDir,
        stdin = os.Inherit,
        stdout = os.Inherit,
        check = false
      ).exitCode
    }

    if (exitCode == 0) {} // do nothing
    else {
      if (ktlintArgs.check) {
        throw new RuntimeException(s"Ktlint exited abnormally with exit code = $exitCode")
      } else {
        ctx.log.error(s"Ktlint exited abnormally with exit code = $exitCode")
      }
    }
  }
}
