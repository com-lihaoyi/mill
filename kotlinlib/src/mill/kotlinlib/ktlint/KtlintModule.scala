package mill.kotlinlib.ktlint

import mainargs.arg
import mill._
import mill.api.{PathRef}
import mill.define.{Discover, ExternalModule}
import mill.javalib.JavaModule
import mill.kotlinlib.DepSyntax
import mill.util.Tasks
import mill.util.Jvm

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
      Seq(ivy"com.pinterest.ktlint:ktlint-cli:${ktlintVersion()}")
    )
  }

  /**
   * Ktlint configuration file.
   */
  def ktlintConfig: T[Option[PathRef]] = Task {
    Some(PathRef(Task.workspace / ".editorconfig"))
  }

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

object KtlintModule extends ExternalModule with KtlintModule with TaskModule {
  override def defaultCommandName(): String = "reformatAll"

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
      filesToFormat: Seq[PathRef],
      config: Option[PathRef],
      options: Seq[String],
      classPath: Seq[PathRef]
  )(implicit ctx: api.Ctx): Unit = {
    if (ktlintArgs.check) {
      ctx.log.info("checking format in kotlin sources ...")
    } else {
      ctx.log.info("formatting kotlin sources ...")
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
    args ++= filesToFormat.map(_.path)
      .filter(f => os.exists(f) && (f.ext == "kt" || f.ext == "kts"))
      .map(_.toString())

    val exitCode = os.checker.withValue(os.Checker.Nop) {
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
        throw new RuntimeException(s"ktlint exited abnormally with exit code = $exitCode")
      } else {
        ctx.log.error(s"ktlint exited abnormally with exit code = $exitCode")
      }
    }
  }
}
