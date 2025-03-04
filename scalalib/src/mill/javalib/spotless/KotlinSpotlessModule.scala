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
   * Defaults to the version specified in build.mill.
   */
  def ktfmtVersion: T[String]

  /**
   * Defaults to the version specified in build.mill.
   */
  def ktlintVersion: T[String]
}
