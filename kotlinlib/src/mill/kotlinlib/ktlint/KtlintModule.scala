package mill.kotlinlib.ktlint

import mill._
import mill.api.{Loose, PathRef}
import mill.define.{Discover, ExternalModule}
import mill.javalib.JavaModule
import mill.kotlinlib.DepSyntax
import mill.util.Jvm

/**
 * Performs formatting checks on Kotlin source files using [[https://pinterest.github.io/ktlint/latest/install/integrations/ Ktlint]].
 */
trait KtlintModule extends JavaModule {

  /**
   * Runs [[https://pinterest.github.io/ktlint/latest/install/integrations/ Ktlint]]
   */
  def ktlint(@mainargs.arg ktlintArgs: KtlintArgs): Command[Unit] = Task.Command {
    KtlintModule.ktlintAction(ktlintArgs, ktlintConfig(), ktlintOptions(), ktlintClasspath())
  }

  /**
   * Classpath for running Ktlint.
   */
  def ktlintClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(ivy"com.pinterest.ktlint:ktlint-cli:${ktlintVersion()}")
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

  lazy val millDiscover: Discover = Discover[this.type]

  /**
   * Reformats Kotlin source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when set, files are not formatted
   */
  def reformatAll(
      check: mainargs.Flag = mainargs.Flag(value = false)
  ): Command[Unit] = Task.Command {
    ktlintAction(
      KtlintArgs(format = true, check = check.value),
      ktlintConfig(),
      ktlintOptions(),
      ktlintClasspath()
    )
  }

  /**
   * Checks the Kotlin source files formatting without reformatting the files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when set, files are not formatted
   */
  def checkFormatAll(
      check: mainargs.Flag = mainargs.Flag(value = false)
  ): Command[Unit] = Task.Command {
    ktlintAction(
      KtlintArgs(format = false, check = check.value),
      ktlintConfig(),
      ktlintOptions(),
      ktlintClasspath()
    )
  }

  private def ktlintAction(
      ktlintArgs: KtlintArgs,
      config: Option[PathRef],
      options: Seq[String],
      classPath: Loose.Agg[PathRef]
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

    val exitCode = Jvm.callSubprocess(
      mainClass = "com.pinterest.ktlint.Main",
      classPath = classPath.map(_.path),
      mainArgs = args.result(),
      workingDir = millSourcePath, // allow passing relative paths for sources like src/a/b
      streamOut = true,
      check = false
    ).exitCode

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
