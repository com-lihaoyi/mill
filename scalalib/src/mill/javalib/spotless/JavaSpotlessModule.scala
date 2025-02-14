package mill.javalib.spotless

import mill.{Agg, PathRef, T, Task}
import mill.scalalib.DepSyntax

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
   * Google Java Format version. Defaults to `1.25.2`.
   */
  def googleJavaFormatVersion: T[String] = Task {
    "1.25.2"
  }

  /**
   * Google Java Format version. Defaults to `2.50.0`.
   */
  def palantirJavaFormatVersion: T[String] = Task {
    "2.50.0"
  }
}
