package mill.kotlinlib.ktlint

import mill._
import mill.api.{Loose, PathRef}
import mill.kotlinlib.{DepSyntax, KotlinModule}
import mill.util.Jvm

/**
 * Performs formatting checks on Kotlin source files using [[https://pinterest.github.io/ktlint/latest/install/integrations/ Ktlint]].
 */
trait KtlintModule extends KotlinModule {

  /**
   * Runs [[https://pinterest.github.io/ktlint/latest/install/integrations/ Ktlint]]
   */
  def ktlint(@mainargs.arg ktlintArgs: KtlintArgs): Command[Unit] = T.command {
    val exitCode = ktlint0(ktlintArgs.format)()

    ktlintHandleErrors(ktlintArgs.check, exitCode)
  }

  private def ktlint0(format: Boolean) = T.task {

    T.log.info("running ktlint ...")

    val configArgument = ktlintConfig() match {
      case Some(path) => Seq("--editorconfig", path.path.toString())
      case None => Seq.empty
    }
    val formatArgument = if (format) Seq("-F") else Seq.empty
    val args = ktlintOptions() ++ configArgument ++ formatArgument

    Jvm.callSubprocess(
      mainClass = "com.pinterest.ktlint.Main",
      classPath = ktlintClasspath().map(_.path),
      mainArgs = args,
      workingDir = millSourcePath, // allow passing relative paths for sources like src/a/b
      streamOut = true,
      check = false
    ).exitCode
  }

  private def ktlintHandleErrors(check: Boolean, exitCode: Int)(implicit ctx: mill.api.Ctx) = {

    if (exitCode == 0) {} // do nothing
    else {
      if (check) {
        throw new RuntimeException(s"ktlint exited abnormally with exit code = $exitCode")
      } else {
        T.log.error(s"ktlint exited abnormally with exit code = $exitCode")
      }
    }
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
    None
  }

  /**
   * Ktlint version.
   */
  def ktlintVersion: T[String] = Task {
    "1.3.1"
  }

  /**
   * Additional arguments for Ktlint. Check [[https://pinterest.github.io/ktlint/latest/install/cli/ available options]].
   */
  def ktlintOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }
}
