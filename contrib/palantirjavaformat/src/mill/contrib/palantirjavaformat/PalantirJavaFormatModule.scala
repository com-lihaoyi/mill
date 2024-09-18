package mill
package contrib.palantirjavaformat

import mill.api.Loose
import mill.api.PathRef
import mill.define.{Discover, ExternalModule}
import mill.main.Tasks
import mill.scalalib.DepSyntax
import mill.scalalib.JavaModule
import mill.util.Jvm

/**
 * Formats Java source files using [[https://github.com/palantir/palantir-java-format Palantir Java Format]].
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

    val _sources =
      if (sources.value.isEmpty) this.sources()
      else sources.value.iterator.map(rel => PathRef(millSourcePath / os.RelPath(rel)))

    PalantirJavaFormatModule.palantirAction(
      _sources,
      check,
      palantirjavaformatOptions(),
      palantirjavaformatClasspath(),
      palantirjavaformatJvmArgs()
    )
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
object PalantirJavaFormatModule extends ExternalModule with PalantirJavaFormatModule {

  /**
   * Formats Java source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when `true`, files are not formatted
   * @param sources list of [[JavaModule]] sources to process
   */
  def formatAll(
      @mainargs.arg check: Boolean = false,
      @mainargs.arg(positional = true) sources: Tasks[Seq[PathRef]]
  ): Command[Unit] = T.command {

    val _sources = T.sequence(sources.value)().iterator.flatten

    palantirAction(
      _sources,
      check,
      palantirjavaformatOptions(),
      palantirjavaformatClasspath(),
      palantirjavaformatJvmArgs()
    )
  }

  lazy val millDiscover: Discover = Discover[this.type]

  private[palantirjavaformat] def palantirAction(
      sources: IterableOnce[PathRef],
      check: Boolean,
      options: PathRef,
      classPath: Loose.Agg[PathRef],
      jvmArgs: Seq[String]
  )(implicit ctx: api.Ctx): Unit = {

    if (check) {
      ctx.log.info("checking format in java sources ...")
    } else {
      ctx.log.info("formatting java sources ...")
    }

    val mainArgs = palantirArgs(sources, check, options)

    ctx.log.debug(s"running palantirjavaformat with $mainArgs")

    val exitCode = Jvm.callSubprocess(
      mainClass = "com.palantir.javaformat.java.Main",
      classPath = classPath.map(_.path),
      jvmArgs = jvmArgs,
      mainArgs = mainArgs,
      workingDir = ctx.dest,
      check = false
    ).exitCode

    if (check && exitCode != 0) {
      ctx.log.error(
        "palantirjavaformat aborted due to format error(s) (or invalid plugin settings/palantirjavaformat options)"
      )
      throw new RuntimeException(s"palantirjavaformat exit($exitCode)")
    }
  }

  private def palantirArgs(
      sources: IterableOnce[PathRef],
      check: Boolean,
      options: PathRef
  ): Seq[String] = {

    val args = Seq.newBuilder[String]

    // https://github.com/palantir/palantir-java-format/blob/dae9be4b84e2bd4d7ea346c6374fda47eee7118f/palantir-java-format/src/main/java/com/palantir/javaformat/java/CommandLineOptionsParser.java#L199
    if (os.exists(options.path)) args += s"@${options.path}"

    // https://github.com/palantir/palantir-java-format/blob/dae9be4b84e2bd4d7ea346c6374fda47eee7118f/palantir-java-format/src/main/java/com/palantir/javaformat/java/CommandLineOptions.java#L27
    if (check) {
      // do not overwrite files and exit(1) if formatting changes were detected
      args += "--dry-run" += "--set-exit-if-changed"
    } else {
      // format in place
      args += "--replace"
    }

    // https://github.com/palantir/palantir-java-format/blob/dae9be4b84e2bd4d7ea346c6374fda47eee7118f/palantir-java-format/src/main/java/com/palantir/javaformat/java/CommandLineOptionsParser.java#L49
    args ++=
      sources
        .iterator
        .map(_.path)
        .flatMap(os.walk(_, includeTarget = true))
        .filter(os.isFile)
        .filter(_.ext == "java")
        .map(_.toString())

    args.result()
  }
}
