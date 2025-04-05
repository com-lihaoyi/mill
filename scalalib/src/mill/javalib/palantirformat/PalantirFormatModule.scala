package mill.javalib.palantirformat

import mill.api.{Ctx, PathRef}
import mill.define.{Discover, ExternalModule, TaskModule}
import mill.util.Tasks
import mill.util.TokenReaders.*
import mill.scalalib.JavaModule
import mill.util.Jvm
import mill.{Command, T, Task}

/**
 * Formats Java source files using [[https://github.com/palantir/palantir-java-format Palantir Java Format]].
 */
trait PalantirFormatModule extends JavaModule with PalantirFormatBaseModule {

  /**
   * Formats Java source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when set, files are not formatted
   * @param sources list of file or folder path(s) to be processed
   *                - path must be relative to [[moduleDir]]
   *                - when empty, all [[sources]] are processed
   */
  def palantirformat(
      check: mainargs.Flag = mainargs.Flag(value = false),
      sources: mainargs.Leftover[String]
  ): Command[Unit] = Task.Command {

    val _sources =
      if (sources.value.isEmpty) this.sources()
      else sources.value.iterator.map(rel => PathRef(moduleDir / os.RelPath(rel)))

    PalantirFormatModule.palantirAction(
      _sources,
      check.value,
      palantirformatOptions(),
      palantirformatClasspath(),
      palantirformatJvmArgs()
    )
  }
}

object PalantirFormatModule extends ExternalModule with PalantirFormatBaseModule with TaskModule {

  override def defaultCommandName(): String = "formatAll"

  /**
   * Formats Java source files.
   *
   * @param check if an exception should be raised when formatting errors are found
   *              - when set, files are not formatted
   * @param sources list of [[JavaModule]] sources to process
   */
  def formatAll(
      check: mainargs.Flag = mainargs.Flag(value = false),
      @mainargs.arg(positional = true) sources: Tasks[Seq[PathRef]] =
        Tasks.resolveMainDefault("__.sources")
  ): Command[Unit] = Task.Command {

    val _sources = Task.sequence(sources.value)().iterator.flatten

    palantirAction(
      _sources,
      check.value,
      palantirformatOptions(),
      palantirformatClasspath(),
      palantirformatJvmArgs()
    )
  }

  lazy val millDiscover = Discover[this.type]

  private[palantirformat] def palantirAction(
      sources: IterableOnce[PathRef],
      check: Boolean,
      options: PathRef,
      classPath: Seq[PathRef],
      jvmArgs: Seq[String]
  )(implicit ctx: Ctx): Unit = {

    val javaFiles = sources
      .iterator
      .map(_.path)
      .filter(os.exists(_))
      .flatMap(os.walk(_, includeTarget = true))
      .filter(os.isFile)
      .filter(_.ext == "java")
      .toSeq

    if (check) {
      ctx.log.info(s"checking format in ${javaFiles.size} java sources ...")
    } else {
      ctx.log.info(s"formatting ${javaFiles.size} java sources ...")
    }

    palantirArgs(javaFiles, check, options) match {
      case None =>

        ctx.log.debug("No sources found, skipping")
      case Some(mainArgs) =>

        ctx.log.debug(s"running palantirformat with $mainArgs")

        val exitCode = Jvm.callProcess(
          mainClass = "com.palantir.javaformat.java.Main",
          classPath = classPath.map(_.path).toVector,
          jvmArgs = jvmArgs,
          mainArgs = mainArgs,
          cwd = ctx.dest,
          stdin = os.Inherit,
          stdout = os.Inherit,
          check = false
        ).exitCode

        if (check && exitCode != 0) {
          ctx.log.error(
            "palantirformat aborted due to format error(s) (or invalid plugin settings/palantirformat options)"
          )
          throw new RuntimeException(s"palantirformat exit($exitCode)")
        }
    }

  }

  private def palantirArgs(
      sourceFiles: Seq[os.Path],
      check: Boolean,
      options: PathRef
  ): Option[Seq[String]] = {

    Option.when(sourceFiles.nonEmpty) {
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
      args ++= sourceFiles.map(_.toString)

      args.result()
    }
  }

  /**
   * Path to options file for Palantir Java Format CLI at `Task.workspace` `/` `palantirformat.options`.
   */
  override def palantirformatOptions: T[PathRef] = Task.Source(
    Task.workspace / "palantirformat.options"
  )
}
