/*
 * Some parts of this code are taken from lefou/mill-jacoco. Copyright 2021-Present Tobias Roeser.
 */

package mill.kotlinlib.kover

import mill._
import mill.api.Result.Success
import mill.api.{Loose, PathRef}
import mill.kotlinlib.{Dep, DepSyntax, Versions}
import mill.scalalib.CoursierModule

trait KoverReportBaseModule extends CoursierModule {

  private[kover] val reportName = "kover-report"

  /**
   * Reads the Kover version from system environment variable `KOVER_VERSION` or defaults to a hardcoded version.
   */
  def koverVersion: T[String] = Task.Input {
    Success[String](Task.env.getOrElse("KOVER_VERSION", Versions.koverVersion))
  }

  def koverCliDep: T[Agg[Dep]] = Task {
    Agg(mvn"org.jetbrains.kotlinx:kover-cli:${koverVersion()}")
  }

  /**
   * Classpath for running Kover.
   */
  def koverCliClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().classpath(koverCliDep())
  }
}
