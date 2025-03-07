package mill.javalib.spotless

import mill.{Agg, PathRef, T, Task}
import mill.scalalib.DepSyntax
import mill.scalalib.api.Versions

trait JavaSpotlessModule extends SpotlessModule {

  /**
   * Classpath for running
   * - Google Java Format
   * - Palantir Java Format
   */
  def jvmLangLibClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(
        ivy"com.google.googlejavaformat:google-java-format:${googleJavaFormatVersion()}",
        ivy"com.palantir.javaformat:palantir-java-format:${palantirJavaFormatVersion()}"
      )
    )
  }

  /**
   * Google Java Format version. Must be defined in build.mill.
   */
  def googleJavaFormatVersion: T[String]

  /**
   * Palantir Java Format version.
   */
  def palantirJavaFormatVersion: T[String] = Task {
    Versions.palantirFormatVersion
  }
}
