package mill
package contrib.palantirjavaformat

import mill.api.Loose
import mill.api.PathRef
import mill.scalalib.DepSyntax
import mill.scalalib.JavaModule
import mill.util.Jvm

/**
 * Formats Java source files using [[https://github.com/palantir/palantir-java-format/ Palantir]].
 */
trait PalantirJavaFormatModule extends JavaModule {

  /**
   * Formats Java source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when `true`, files are not formatted
   * @param sources list of file or folder path(s) to be processed
   *                - path must be relative to [[millSourcePath]]
   *                - when empty, all [[sources]] are processed
   */
  def javafmt(
      @mainargs.arg check: Boolean = false,
      sources: mainargs.Leftover[String]
  ): Command[Unit] = T.command {

    if (check) {
      T.log.info("checking format in java sources ...")
    } else {
      T.log.info("formatting java sources ...")
    }

    val args = PalantirJavaFormatModule.mainArgs(
      if (sources.value.isEmpty) this.sources().iterator.map(_.path)
      else sources.value.iterator.map(rel => millSourcePath / os.RelPath(rel)),
      palantirjavaformatOptions().path,
      check
    )

    T.log.debug(s"running palantirjavaformat with $args")

    val exitCode = Jvm.callSubprocess(
      mainClass = PalantirJavaFormatModule.mainClass,
      classPath = palantirjavaformatClasspath().map(_.path),
      jvmArgs = palantirjavaformatJvmArgs(),
      mainArgs = args,
      workingDir = T.dest,
      check = false
    ).exitCode

    if (check && exitCode != 0) {
      throw new RuntimeException(s"palantirjavaformat exit($exitCode), format error(s) found")
    }
  }

  /**
   * Classpath for running Palantir.
   */
  def palantirjavaformatClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.palantir.javaformat:palantir-java-format:${palantirjavaformatVersion()}")
    )
  }

  /**
   * JVM arguments for running Palantir. Defaults to values prescribed in
   * "[[https://github.com/palantir/palantir-java-format/issues/548 Broken on Java 16]]".
   */
  def palantirjavaformatJvmArgs: T[Seq[String]] = T {
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
   * Path to options file for Palantir. Defaults to [[millSourcePath]] `/` `palantirjavaformat.options`.
   */
  def palantirjavaformatOptions: T[PathRef] = T.source {
    millSourcePath / "palantirjavaformat.options"
  }

  /**
   * Palantir version. Defaults to `2.50.0`.
   */
  def palantirjavaformatVersion: T[String] = T {
    "2.50.0"
  }
}
object PalantirJavaFormatModule {

  private[palantirjavaformat] def mainArgs(
      sources: IterableOnce[os.Path],
      options: os.Path,
      check: Boolean = false
  ): Seq[String] = {

    val args = Seq.newBuilder[String]

    if (os.exists(options)) args += s"@$options"

    if (check) {
      // do not overwrite files and exit(1) if formatting changes were detected
      args += "--dry-run" += "--set-exit-if-changed"
    } else {
      // format in place
      args += "--replace"
    }

    args ++=
      sources
        .iterator
        .flatMap(os.walk(_, includeTarget = true))
        .filter(os.isFile)
        .filter(_.ext == "java")
        .map(_.toString())

    args.result()
  }

  private[palantirjavaformat] def mainClass: String = "com.palantir.javaformat.java.Main"
}
