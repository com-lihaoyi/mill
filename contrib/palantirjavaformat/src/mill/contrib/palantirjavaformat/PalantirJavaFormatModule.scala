package mill
package contrib.palantirjavaformat

import mill.api.Loose
import mill.api.PathRef
import mill.scalalib.DepSyntax
import mill.scalalib.JavaModule
import mill.util.Jvm

trait PalantirJavaFormatModule extends JavaModule {

  def javafmt(
      @mainargs.arg check: Boolean = false,
      sourceFilters: mainargs.Leftover[String]
  ): Command[Unit] = T.command {

    val _sources =
      if (sourceFilters.value.isEmpty) sources().iterator.map(_.path)
      else sourceFilters.value.iterator.map(rel => millSourcePath / os.RelPath(rel))

    if (check) {
      T.log.info("checking format in java sources ...")
    } else {
      T.log.info("formatting java sources ...")
    }

    val exitCode = Jvm.callSubprocess(
      mainClass = PalantirJavaFormatModule.mainClass,
      classPath = palantirjavaformatClasspath().map(_.path),
      jvmArgs = PalantirJavaFormatModule.jvmArgs,
      mainArgs =
        PalantirJavaFormatModule.mainArgs(_sources, palantirjavaformatOptions().path, check),
      workingDir = T.dest,
      check = false
    ).exitCode

    if (check && exitCode != 0) {
      throw new RuntimeException(s"palantir exit($exitCode), format error(s) found")
    }
  }

  def palantirjavaformatClasspath: T[Loose.Agg[PathRef]] = T {
    defaultResolver().resolveDeps(
      Agg(ivy"com.palantir.javaformat:palantir-java-format:${palantirjavaformatVersion()}")
    )
  }

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

  // https://github.com/palantir/palantir-java-format/issues/548
  val jvmArgs: Seq[String] = Seq(
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

  def mainArgs(
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

  def mainClass: String = "com.palantir.javaformat.java.Main"
}
