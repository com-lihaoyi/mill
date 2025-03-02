package mill.javalib.spotless

import mill.scalalib.DepSyntax
import mill.{Agg, PathRef, T, Task}

trait ScalaSpotlessModule extends SpotlessModule {

  /**
   * Classpath for running Scala Format.
   */
  def jvmLangLibClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(ivy"org.scalameta:scalafmt-core_2.13:${scalafmtVersion()}")
    )
  }

  /**
   * Scala Format version. Must be defined in build.mill.
   */
  def scalafmtVersion: T[String]
}
