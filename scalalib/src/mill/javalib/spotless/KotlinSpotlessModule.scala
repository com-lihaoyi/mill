package mill.javalib.spotless

import mill.scalalib.DepSyntax
import mill.{Agg, PathRef, T, Task}

trait KotlinSpotlessModule extends SpotlessModule {

  /**
   * Classpath for running
   * - Ktfmt
   * - ktlint
   */
  def jvmLangLibClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(
        ivy"com.facebook:ktfmt:${ktfmtVersion()}",
        ivy"com.pinterest.ktlint:ktlint-cli:${ktlintVersion()}"
      )
    )
  }

  /**
   * Defaults to `0.53`.
   */
  def ktfmtVersion: T[String] = Task {
    "0.53"
  }

  /**
   * Defaults to `1.5.0`.
   */
  def ktlintVersion: T[String] = Task {
    "1.5.0"
  }
}
