package mill.kotlinlib.detekt

import mill._
import mill.api.{Loose, PathRef}
import mill.kotlinlib.{DepSyntax, KotlinModule, Versions}
import mill.util.Jvm

/**
 * Performs quality checks on Kotlin source files using [[https://detekt.dev// Detekt]].
 */
trait DetektModule extends KotlinModule {

  /**
   * Runs [[https://detekt.dev/docs/gettingstarted/cli Detekt]]
   */
  def detekt(@mainargs.arg detektArgs: DetektArgs): Command[Unit] = T.command {
    val exitCode = detekt0()()

    detektHandleErrors(detektArgs.check, exitCode)
  }

  private def detekt0() = T.task {

    val args = detektOptions() ++ Seq("-i", T.workspace.toString()) ++
      Seq("-c", detektConfig().path.toString())

    T.log.info("running detekt ...")
    T.log.debug(s"with $args")

    Jvm.callSubprocess(
      mainClass = "io.gitlab.arturbosch.detekt.cli.Main",
      classPath = detektClasspath().map(_.path),
      mainArgs = args,
      workingDir = millSourcePath, // allow passing relative paths for sources like src/a/b
      streamOut = true,
      check = false
    ).exitCode
  }

  private def detektHandleErrors(check: Boolean, exitCode: Int)(implicit ctx: mill.api.Ctx) = {

    if (exitCode == 0) {} // do nothing
    else if (exitCode == 1) {
      throw new RuntimeException("detekt: An unexpected error occurred")
    } else if (exitCode == 2) {
      if (check) {
        throw new RuntimeException("detekt: Max issues was reached")
      } else {
        T.log.error("detekt: Max issues was reached")
      }
    } else if (exitCode == 3) {
      throw new RuntimeException("detekt: Invalid configuration file detected")
    } else {
      throw new RuntimeException(s"detekt exited abnormally with exit code = $exitCode")
    }
  }

  /**
   * Classpath for running Dekekt.
   */
  def detektClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(ivy"io.gitlab.arturbosch.detekt:detekt-cli:${detektVersion()}")
    )
  }

  /**
   * Detekt configuration file. Defaults to `detekt-config.yml`.
   */
  def detektConfig: T[PathRef] = Task {
    PathRef(T.workspace / "detekt-config.yml")
  }

  /**
   * Detekt version.
   */
  def detektVersion: T[String] = Task {
    Versions.detektVersion
  }

  /**
   * Additional arguments for Detekt. Check [[https://detekt.dev/docs/gettingstarted/cli/ available options]].
   */
  def detektOptions: T[Seq[String]] = Task {
    Seq.empty[String]
  }
}
