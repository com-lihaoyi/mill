package mill.javalib.palantirformat

import mill.api.{Loose, PathRef}
import mill.scalalib.{CoursierModule, DepSyntax}
import mill.{Agg, T, Task}

trait PalantirFormatBaseModule extends CoursierModule {

  /**
   * Classpath for running Palantir Java Format.
   */
  def palantirformatClasspath: T[Loose.Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Agg(ivy"com.palantir.javaformat:palantir-java-format:${palantirformatVersion()}")
    )
  }

  /**
   * JVM arguments for running Palantir Java Format. Defaults to values prescribed in
   * "[[https://github.com/palantir/palantir-java-format/issues/548 Broken on Java 16]]".
   */
  def palantirformatJvmArgs: T[Seq[String]] = Task {
    Seq(
      "--add-exports",
      "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      "--add-exports",
      "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
      "--add-exports",
      "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
      "--add-exports",
      "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
      "--add-exports",
      "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
  }

  /**
   * Path to options file for Palantir Java Format CLI. Defaults to `millSourcePath` `/` `palantirformat.options`.
   */
  def palantirformatOptions: T[PathRef] = Task.Source(
    millSourcePath / "palantirformat.options"
  )

  /**
   * Palantir Java Format version. Defaults to `2.50.0`.
   */
  def palantirformatVersion: T[String] = Task {
    "2.50.0"
  }
}
